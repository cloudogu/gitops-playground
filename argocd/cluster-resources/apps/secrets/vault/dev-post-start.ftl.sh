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

main() {

  waitForVault
  
  createSecretsForExampleApp
  
  createUserAccount "$USERNAME" "$PASSWORD"
  
  enableKubernetesAuth
  
  if [ "$ARGOCD" = 'true' ]; then
    authorizeServiceAccountsFromArgoCDExamples
  fi
}

waitForVault() {
  # Avoid: dial tcp 127.0.0.1:8200: connect: connection refused..
  # Unfortunately, busybox wget doesnt have --retry-connrefused, so use a loop
  timeout 30s sh -c "until wget -O/dev/null -q http://127.0.0.1:8200/; do sleep 1; done"
}

createSecretsForExampleApp() {
  vault kv put secret/staging/nginx-helm-jenkins example=staging-secret
  vault kv put secret/production/nginx-helm-jenkins example=production-secret
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

main "$@"
</#noparse>
