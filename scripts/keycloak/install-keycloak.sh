#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

KEYCLOAK_NAMESPACE="${KEYCLOAK_NAMESPACE:-keycloak}"
KEYCLOAK_HOST="${KEYCLOAK_HOST:-keycloak.local.gd}"
REALM_EXPORT="${REALM_EXPORT:-docs/oidc/realm-export.json}"

kubectl create namespace "${KEYCLOAK_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "${KEYCLOAK_NAMESPACE}" create configmap keycloak-realm \
  --from-file=realm-export.json="${REALM_EXPORT}" \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install keycloak oci://registry-1.docker.io/bitnamicharts/keycloak \
  --namespace "${KEYCLOAK_NAMESPACE}" \
  --reset-values \
  --set global.security.allowInsecureImages=true \
  --set image.registry=docker.io \
  --set image.repository=bitnamilegacy/keycloak \
  --set postgresql.image.registry=docker.io \
  --set postgresql.image.repository=bitnamilegacy/postgresql \
  --set auth.adminUser=admin \
  --set auth.adminPassword=admin \
  --set production=false \
  --set tls.enabled=false \
  --set proxyHeaders=xforwarded \
  --set hostnameStrict=false \
  --set httpEnabled=true \
  --set extraEnvVars[0].name=KC_HOSTNAME \
  --set extraEnvVars[0].value="${KEYCLOAK_HOST}" \
  --set ingress.enabled=true \
  --set ingress.ingressClassName=traefik \
  --set ingress.hostname="${KEYCLOAK_HOST}" \
  --set ingress.tls=false \
  --set keycloakConfigCli.enabled=false \
  --set extraStartupArgs=--import-realm \
  --set extraVolumes[0].name=realm-import \
  --set extraVolumes[0].configMap.name=keycloak-realm \
  --set extraVolumeMounts[0].name=realm-import \
  --set extraVolumeMounts[0].mountPath=/opt/bitnami/keycloak/data/import/realm-export.json \
  --set extraVolumeMounts[0].subPath=realm-export.json \
  --set extraVolumeMounts[0].readOnly=true

tmp_override="$(mktemp)"
cat > "${tmp_override}" <<EOF
rewrite name ${KEYCLOAK_HOST} keycloak.${KEYCLOAK_NAMESPACE}.svc.cluster.local
EOF
if kubectl -n kube-system get configmap coredns-custom >/dev/null 2>&1; then
  echo "WARNING: kube-system/coredns-custom already exists; this script will overwrite it." >&2
fi
kubectl -n kube-system create configmap coredns-custom \
  --from-file=keycloak.override="${tmp_override}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n kube-system rollout restart deployment/coredns
rm -f "${tmp_override}"

kubectl -n "${KEYCLOAK_NAMESPACE}" rollout status statefulset/keycloak --timeout=10m