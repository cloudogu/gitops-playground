#!/usr/bin/env bash

set -o errexit
set -o nounset
set -o pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER_NAME="external-vault"
NAMESPACE="external-vault-test"
VAULT_TOKEN="dev-only-token"

# Keep the fixture options consistent with prepare-test-vault.sh.
for argument in "$@"; do
  case "${argument}" in
    --container-name=*) CONTAINER_NAME="${argument#*=}" ;;
    --namespace=*) NAMESPACE="${argument#*=}" ;;
    --vault-token=*) VAULT_TOKEN="${argument#*=}" ;;
    --host-port=*|--vault-image=*) ;;
    *) echo "Unknown argument: ${argument}" >&2; exit 1 ;;
  esac
done

# Reuse the existing external Vault fixture, without placing a root token in Kubernetes.
"${SCRIPT_DIR}/prepare-test-vault.sh" --skip-token-secret "$@"

VAULT_CLI=(
  docker exec -i
  -e VAULT_ADDR=http://127.0.0.1:8200
  -e "VAULT_TOKEN=${VAULT_TOKEN}"
  "${CONTAINER_NAME}" vault
)

"${VAULT_CLI[@]}" auth enable approle >/dev/null
"${VAULT_CLI[@]}" policy write gop-eso-read - >/dev/null <<'POLICY'
path "secret/data/gop/customer-test" {
  capabilities = ["read"]
}

# ESO validates the AppRole-issued token using lookup-self.
path "auth/token/lookup-self" {
  capabilities = ["read"]
}
POLICY

"${VAULT_CLI[@]}" write auth/approle/role/gop-eso \
  bind_secret_id=true \
  token_policies=gop-eso-read \
  token_no_default_policy=true \
  token_ttl=1h \
  token_max_ttl=4h \
  secret_id_ttl=24h \
  secret_id_num_uses=0 >/dev/null

# A stable RoleID keeps the local GOP test profile reproducible; never use it in production.
"${VAULT_CLI[@]}" write auth/approle/role/gop-eso/role-id \
  role_id=gop-eso-local-test >/dev/null

# Stream the SecretID directly into Kubernetes, removing the CLI's trailing newline.
# Neither the SecretID nor a Kubernetes Secret manifest is printed or stored in Git.
"${VAULT_CLI[@]}" write -field=secret_id -force auth/approle/role/gop-eso/secret-id \
  | tr -d '\r\n' \
  | kubectl -n "${NAMESPACE}" create secret generic vault-approle \
      --from-file=secret-id=/dev/stdin --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null

printf 'AppRole fixture ready in namespace %s (Secret: vault-approle)\n' "${NAMESPACE}" >&2
