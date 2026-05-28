#!/usr/bin/env sh
# No bash in vault container, only plain shell!
<#noparse>
set -o errexit -o nounset
# No pipefail in plain shell (!= bash)
#-o pipefail
# Write executed commands for easier debugging
set -x
# Parameters (via env vars)
# USERNAME, PASSWORD -> Human user account created in vault
# ARGOCD -> Allows read access for service accounts within example-apps-staging and -production namespaces used by external secrets operator
# OIDC_ENABLED -> Enable OIDC auth method
# OIDC_DISCOVERY_URL -> OIDC discovery URL (e.g. http://keycloak.localhost/realms/gop)
# OIDC_CLIENT_ID -> OIDC client ID
# OIDC_CLIENT_SECRET -> OIDC client secret
# VAULT_EXTERNAL_URL -> External URL of vault (for OIDC redirect URIs)

main() {
  waitForVault

  createUserAccount "$USERNAME" "$PASSWORD"

  enableKubernetesAuth

  if [ "$ARGOCD" = 'true' ]; then
    authorizeServiceAccountsFromArgoCDExamples
  fi

  if [ "${OIDC_ENABLED}" = 'true' ]; then
    enableOidc
  fi
}

waitForVault() {
  # Avoid: dial tcp 127.0.0.1:8200: connect: connection refused..
  # Unfortunately, busybox wget doesnt have --retry-connrefused, so use a loop
  timeout 30s sh -c "until wget -O/dev/null -q http://127.0.0.1:8200/; do sleep 1; done"
}

createUserAccount() {
  USERNAME=$1
  PASSWORD=$2
  # Create policy
  vault policy write secret-editor - <<EOF
path "secret/*" {
  capabilities = ["create", "read", "update", "patch", "delete", "list"]
}
EOF
  vault auth enable userpass
  # Create and authorize user via policy
  vault write auth/userpass/users/$USERNAME password="$PASSWORD" policies=secret-editor
}

enableKubernetesAuth() {
  # Enable access for kubernetes service accounts

  # https://developer.hashicorp.com/vault/tutorials/kubernetes/kubernetes-external-vault
  vault auth enable kubernetes

  # https://developer.hashicorp.com/vault/docs/auth/kubernetes#use-local-service-account-token-as-the-reviewer-jwt
  # Makes vault access the k8s API to find a service account matching an access token
  # Vault then checks if the k8s-SA is authorized via a vault-role. A vault-role brings together a k8s-SA + vault-policy.
  # The vault-policy allows access to a secret
  # vault-roles and vault-policies are created in authorizeServiceAccountsFromArgoCDExamples()
  vault write auth/kubernetes/config \
      kubernetes_host=https://$KUBERNETES_SERVICE_HOST:$KUBERNETES_SERVICE_PORT
}

authorizeServiceAccountsFromArgoCDExamples() {
  #  Authorize service accounts within argoCD-deployed namespaces used by external secrets operator configured via SecretStore

  for STAGE in staging production
  do
    POLICY=${STAGE}-read

    vault policy write ${POLICY} - <<EOF
path "secret/data/${STAGE}/*" {
  capabilities = ["read"]
}
EOF
    vault write auth/kubernetes/role/${STAGE} \
         bound_service_account_names=external-secrets-vault-reader \
</#noparse>
         bound_service_account_namespaces=${namePrefix}example-apps-${r"${STAGE}"} \
<#noparse>
         policies=${POLICY} \
         ttl=24h
  done
}

enableOidc() {
  echo "=== OIDC-Konfiguration ==="
  echo "OIDC_DISCOVERY_URL = $OIDC_DISCOVERY_URL"
  echo "OIDC_CLIENT_ID     = $OIDC_CLIENT_ID"
  echo "OIDC_CLIENT_SECRET = $OIDC_CLIENT_SECRET"
  echo "VAULT_EXTERNAL_URL = $VAULT_EXTERNAL_URL"
  echo "redirect_uri (ui)  = $VAULT_EXTERNAL_URL/ui/vault/auth/oidc/oidc/callback"
  echo "redirect_uri (cli) = $VAULT_EXTERNAL_URL/oidc/callback"
  echo "=========================="

  vault auth enable oidc 2>/dev/null || true

  vault write auth/oidc/config \
    oidc_discovery_url="$OIDC_DISCOVERY_URL" \
    oidc_client_id="$OIDC_CLIENT_ID" \
    oidc_client_secret="$OIDC_CLIENT_SECRET" \
    default_role="default"

  vault write auth/oidc/role/default \
    role_type="oidc" \
    bound_audiences="$OIDC_CLIENT_ID" \
    allowed_redirect_uris="$VAULT_EXTERNAL_URL/ui/vault/auth/oidc/oidc/callback" \
    allowed_redirect_uris="$VAULT_EXTERNAL_URL/oidc/callback" \
    user_claim="sub" \
    groups_claim="groups" \
    policies="default" \
    ttl="1h"


  echo "OIDC configured"
}

main "$@"
</#noparse>