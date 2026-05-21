# Deploy a local Keycloak as OIDC provider

Install Keycloak via Helm into the `keycloak` namespace.

```bash
helm upgrade --install keycloak oci://registry-1.docker.io/bitnamicharts/keycloak \
  --namespace keycloak --create-namespace \
  --set image.registry=docker.io \
  --set image.repository=bitnamilegacy/keycloak \
  --set postgresql.image.registry=docker.io \
  --set postgresql.image.repository=bitnamilegacy/postgresql \
  --set keycloakConfigCli.image.registry=docker.io \
  --set keycloakConfigCli.image.repository=bitnamilegacy/keycloak-config-cli \
  --set auth.adminUser=admin \
  --set auth.adminPassword=admin \
  --set production=false \
  --set tls.enabled=false \
  --set proxy=edge \
  --set extraEnvVars[0].name=KC_HOSTNAME \
  --set extraEnvVars[0].value=keycloak.localhost \
  --set extraEnvVars[1].name=KC_HOSTNAME_STRICT \
  --set "extraEnvVars[1].value=false" \
  --set extraEnvVars[2].name=KC_HOSTNAME_STRICT_BACKCHANNEL \
  --set "extraEnvVars[2].value=false" \
  --set extraEnvVars[3].name=KC_HTTP_ENABLED \
  --set "extraEnvVars[3].value=true" \
  --set ingress.enabled=true \
  --set ingress.ingressClassName=traefik \
  --set ingress.hostname=keycloak.localhost \
  --set ingress.tls=false
```

After the rollout, Keycloak is reachable at `http://keycloak.localhost`. Admin UI: `http://keycloak.localhost/admin` (
admin / admin).