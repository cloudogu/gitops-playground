#!/usr/bin/env bash

set -o errexit
set -o nounset
set -o pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=/dev/null
source "${PROJECT_ROOT}/src/test/resources/external-vault-test-fixture.properties"

CONTAINER_NAME="external-vault"
HOST_PORT="8200"
NAMESPACE="external-vault-test"
VAULT_TOKEN="dev-only-token"
SECRET_PATH="secret/gop/customer-test"
SECRET_USERNAME="${EXTERNAL_VAULT_TEST_USERNAME}"
SECRET_PASSWORD="${EXTERNAL_VAULT_TEST_PASSWORD}"
CREATE_TOKEN_SECRET=true
# renovate: depName=hashicorp/vault datasource=docker
VAULT_IMAGE="hashicorp/vault:2.2.1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container-name=*) CONTAINER_NAME="${1#*=}" ;;
    --host-port=*) HOST_PORT="${1#*=}" ;;
    --namespace=*) NAMESPACE="${1#*=}" ;;
    --vault-token=*) VAULT_TOKEN="${1#*=}" ;;
    --vault-image=*) VAULT_IMAGE="${1#*=}" ;;
    --skip-token-secret) CREATE_TOKEN_SECRET=false ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
  shift
done

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required" >&2
  exit 1
fi
if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl is required" >&2
  exit 1
fi

# Do not create test secrets in a customer cluster when the wrong kubeconfig is selected.
CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null || true)"
if [[ "${CURRENT_CONTEXT}" != k3d-* ]]; then
  echo "Refusing external Vault fixture setup: current Kubernetes context is not k3d (${CURRENT_CONTEXT:-none})" >&2
  exit 1
fi
K3D_CLUSTER="${CURRENT_CONTEXT#k3d-}"
if [[ "$(docker inspect --format '{{.State.Running}}' "k3d-${K3D_CLUSTER}-server-0" 2>/dev/null)" != "true" ]]; then
  echo "Refusing external Vault fixture setup: k3d cluster ${K3D_CLUSTER} is not running in Docker" >&2
  exit 1
fi

# This fixture is intentionally separate from the k3d cluster: GOP must connect to a Vault it did not deploy.
docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true

if [[ "${HOST_PORT}" == "0" ]]; then
  PORT_MAPPING="0.0.0.0::8200"
else
  PORT_MAPPING="0.0.0.0:${HOST_PORT}:8200"
fi

docker run --rm -d \
  --name "${CONTAINER_NAME}" \
  --cap-add=IPC_LOCK \
  -p "${PORT_MAPPING}" \
  "${VAULT_IMAGE}" \
  server -dev \
  -dev-listen-address=0.0.0.0:8200 \
  -dev-root-token-id="${VAULT_TOKEN}" >/dev/null

for _ in $(seq 1 60); do
  if docker exec \
      -e VAULT_ADDR=http://127.0.0.1:8200 \
      -e VAULT_TOKEN="${VAULT_TOKEN}" \
      "${CONTAINER_NAME}" \
      vault status >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec \
  -e VAULT_ADDR=http://127.0.0.1:8200 \
  -e VAULT_TOKEN="${VAULT_TOKEN}" \
  "${CONTAINER_NAME}" \
  vault kv put "${SECRET_PATH}" \
    username="${SECRET_USERNAME}" \
    password="${SECRET_PASSWORD}" >/dev/null

kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if [[ "${CREATE_TOKEN_SECRET}" == "true" ]]; then
  kubectl -n "${NAMESPACE}" create secret generic vault-token \
    --from-literal=token="${VAULT_TOKEN}" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
fi

MAPPED_PORT=$(docker port "${CONTAINER_NAME}" 8200/tcp | head -n 1 | sed 's/.*://')
printf 'http://host.k3d.internal:%s\n' "${MAPPED_PORT}"
