#!/usr/bin/env bash

set -o errexit
set -o nounset
set -o pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER_NAME="external-vault"
NAMESPACE="external-vault-test"
VAULT_TOKEN="dev-only-token"
K3D_CLUSTER=""
AUTH_SERVICE_ACCOUNT="eso-vault-auth"
REVIEWER_SERVICE_ACCOUNT="vault-token-reviewer"
ESO_NAMESPACE="secrets"
ESO_SERVICE_ACCOUNT="external-secrets"
VAULT_FIXTURE_ARGS=()

for argument in "$@"; do
  case "${argument}" in
    --container-name=*) CONTAINER_NAME="${argument#*=}"; VAULT_FIXTURE_ARGS+=("${argument}") ;;
    --namespace=*) NAMESPACE="${argument#*=}"; VAULT_FIXTURE_ARGS+=("${argument}") ;;
    --vault-token=*) VAULT_TOKEN="${argument#*=}"; VAULT_FIXTURE_ARGS+=("${argument}") ;;
    --host-port=*|--vault-image=*) VAULT_FIXTURE_ARGS+=("${argument}") ;;
    --k3d-cluster=*) K3D_CLUSTER="${argument#*=}" ;;
    *) echo "Unknown argument: ${argument}" >&2; exit 1 ;;
  esac
done

for required_command in docker kubectl base64; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    echo "${required_command} is required" >&2
    exit 1
  fi
done

CURRENT_CONTEXT="$(kubectl config current-context)"
if [[ -z "${K3D_CLUSTER}" ]]; then
  if [[ "${CURRENT_CONTEXT}" != k3d-* ]]; then
    echo "This test requires a k3d context or --k3d-cluster=<name>" >&2
    exit 1
  fi
  K3D_CLUSTER="${CURRENT_CONTEXT#k3d-}"
fi
if [[ "${CURRENT_CONTEXT}" != "k3d-${K3D_CLUSTER}" ]]; then
  echo "Current kubectl context must be k3d-${K3D_CLUSTER}; refusing to configure a different cluster" >&2
  exit 1
fi

K3D_NETWORK="k3d-${K3D_CLUSTER}"
K3D_SERVER_CONTAINER="k3d-${K3D_CLUSTER}-server-0"
if ! docker network inspect "${K3D_NETWORK}" >/dev/null 2>&1; then
  echo "Docker network ${K3D_NETWORK} does not exist; start the k3d cluster first" >&2
  exit 1
fi
if ! docker inspect "${K3D_SERVER_CONTAINER}" >/dev/null 2>&1; then
  echo "k3d API server container ${K3D_SERVER_CONTAINER} does not exist" >&2
  exit 1
fi

# The Vault container runs outside Kubernetes, but needs to reach the k3d API server for TokenReview.
API_SERVER_IP="$(docker inspect --format "{{with index .NetworkSettings.Networks \"${K3D_NETWORK}\"}}{{.IPAddress}}{{end}}" "${K3D_SERVER_CONTAINER}")"
if [[ -z "${API_SERVER_IP}" ]]; then
  echo "Could not determine the API server IP on ${K3D_NETWORK}" >&2
  exit 1
fi
KUBERNETES_HOST="https://${API_SERVER_IP}:6443"

# Use the cluster CA; never turn off Kubernetes API TLS verification.
KUBE_CA_CERT="$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 --decode)"
if [[ -z "${KUBE_CA_CERT}" ]]; then
  echo "Could not load the Kubernetes API server CA from the current kubeconfig" >&2
  exit 1
fi
KUBE_CA_CERT="${KUBE_CA_CERT}"$'\n'

# Reuse the existing external Vault fixture without writing a static Vault root token to Kubernetes.
"${SCRIPT_DIR}/prepare-test-vault.sh" --skip-token-secret "${VAULT_FIXTURE_ARGS[@]}"
docker network connect "${K3D_NETWORK}" "${CONTAINER_NAME}" >/dev/null

kubectl -n "${NAMESPACE}" create serviceaccount "${AUTH_SERVICE_ACCOUNT}" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "${NAMESPACE}" create serviceaccount "${REVIEWER_SERVICE_ACCOUNT}" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# Only the designated ESO service account may request tokens for the Vault auth identity.
kubectl apply -f - >/dev/null <<MANIFEST
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: eso-vault-token-request
  namespace: ${NAMESPACE}
rules:
  - apiGroups: [""]
    resources: ["serviceaccounts/token"]
    resourceNames: ["${AUTH_SERVICE_ACCOUNT}"]
    verbs: ["create"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: eso-vault-token-request
  namespace: ${NAMESPACE}
subjects:
  - kind: ServiceAccount
    name: ${ESO_SERVICE_ACCOUNT}
    namespace: ${ESO_NAMESPACE}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: eso-vault-token-request
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: ${NAMESPACE}-vault-token-reviewer
subjects:
  - kind: ServiceAccount
    name: ${REVIEWER_SERVICE_ACCOUNT}
    namespace: ${NAMESPACE}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:auth-delegator
MANIFEST

VAULT_CLI=(
  docker exec -i
  -e VAULT_ADDR=http://127.0.0.1:8200
  -e "VAULT_TOKEN=${VAULT_TOKEN}"
  "${CONTAINER_NAME}" vault
)

"${VAULT_CLI[@]}" auth enable kubernetes >/dev/null
"${VAULT_CLI[@]}" policy write gop-eso-kubernetes-read - >/dev/null <<'POLICY'
path "secret/data/gop/customer-test" {
  capabilities = ["read"]
}

# ESO validates Vault-issued tokens via lookup-self even when the default policy is disabled.
path "auth/token/lookup-self" {
  capabilities = ["read"]
}
POLICY

# The reviewer token is intentionally short-lived and never printed or stored in Git or a Kubernetes Secret.
# Rerun this test fixture if TokenReview starts failing after the reviewer token expires.
kubectl -n "${NAMESPACE}" create token "${REVIEWER_SERVICE_ACCOUNT}" --duration=4h \
  | "${VAULT_CLI[@]}" write auth/kubernetes/config \
      "kubernetes_host=${KUBERNETES_HOST}" \
      "kubernetes_ca_cert=${KUBE_CA_CERT}" \
      token_reviewer_jwt=- >/dev/null

"${VAULT_CLI[@]}" write auth/kubernetes/role/gop-eso \
  "bound_service_account_names=${AUTH_SERVICE_ACCOUNT}" \
  "bound_service_account_namespaces=${NAMESPACE}" \
  audience=vault \
  token_policies=gop-eso-kubernetes-read \
  token_no_default_policy=true \
  token_ttl=1h >/dev/null

# Verify the complete Kubernetes -> Vault -> Kubernetes TokenReview path before GOP starts.
kubectl -n "${NAMESPACE}" create token "${AUTH_SERVICE_ACCOUNT}" --audience=vault --duration=10m \
  | "${VAULT_CLI[@]}" write auth/kubernetes/login role=gop-eso jwt=- >/dev/null

printf 'Kubernetes Auth fixture ready in namespace %s (ServiceAccount: %s, Audience: vault)\n' \
  "${NAMESPACE}" "${AUTH_SERVICE_ACCOUNT}"
printf 'Vault uses Kubernetes TokenReview via %s; reviewer token is short-lived (requested TTL: 4h)\n' \
  "${KUBERNETES_HOST}"
