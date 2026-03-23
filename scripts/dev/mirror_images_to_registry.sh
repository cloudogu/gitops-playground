#!/bin/bash
set -xeo pipefail

# This scripts copies images needed for testing purposes into an harbor instance running at $1
# Usage: ./mirror_images_to_registry.sh <HARBOR_BASE_URL>

HARBOR_BASE_URL=$1
HARBOR_DOCKER_BASE_URL=docker:$(echo $HARBOR_BASE_URL | cut -d: -f2-)

MAILHOG_IMAGE="docker://ghcr.io/cloudogu/mailhog:v1.0.1"
ESO_IMAGE="docker://ghcr.io/external-secrets/external-secrets:v0.9.16"
VAULT_IMAGE="docker://hashicorp/vault:1.14.0"
NGINX_IMAGE="docker://bitnamilegacy/nginx:1.23.3-debian-11-r8"
TRAEFIK_IMAGE="docker://docker.io/library/traefik:v3.3.3"

PROMETHEUS_IMAGE="docker://quay.io/prometheus/prometheus:v3.8.0"
PROMETHEUS_OPERATOR_IMAGE="docker://quay.io/prometheus-operator/prometheus-operator:v0.87.1"
PROMETHEUS_OPERATOR_CONFIG_RELOADER="docker://quay.io/prometheus-operator/prometheus-config-reloader:v0.87.1"
GRAFANA_IMAGE="docker://docker.io/grafana/grafana:12.3.0"
K8S_SIDECAR="docker://quay.io/kiwigrid/k8s-sidecar:2.1.2"

CERT_MANAGER_CONTROLLER="docker://quay.io/jetstack/cert-manager-controller:v1.16.1"
CERT_MANAGER_CA_INJECTOR="docker://quay.io/jetstack/cert-manager-cainjector:v1.16.1"
CERT_MANAGER_WEBHOOK="docker://quay.io/jetstack/cert-manager-webhook:v1.16.1"

KUBECTL_IMAGE="docker://bitnamilegacy/kubectl:latest"
TEMURIN_IMAGE="docker://eclipse-temurin:17-jre-alpine"
HELM_IMAGE="docker://ghcr.io/cloudogu/helm:latest"
MVN_IMAGE="docker://maven:3-eclipse-temurin-17-alpine"
YAMLLINT_IMAGE="docker://cytopia/yamllint:1.25-0.7"

# Hit the API to see when harbor is ready
until curl -s -o /dev/null -w "%{http_code}" $HARBOR_BASE_URL/api/v2.0/projects | grep -q "200"; do
    echo "Waiting for harbor"
    sleep 1
done

declare -A roles
roles['maintainer']='4'
roles['limited-guest']='5'

operations=("Proxy" "Registry")
readOnlyUser='RegistryRead'

for operation in "${operations[@]}"; do

    # Convert the operation to lowercase for the project name and email
    lower_operation=$(echo "$operation" | tr '[:upper:]' '[:lower:]')

    echo "creating project ${lower_operation}"
    projectId=$(curl -is --fail "$HARBOR_BASE_URL/api/v2.0/projects" -X POST -u admin:Harbor12345 -H 'Content-Type: application/json' --data-raw "{\"project_name\":\"$lower_operation\",\"metadata\":{\"public\":\"false\"},\"storage_limit\":-1,\"registry_id\":null}" | grep -i 'Location:' | awk '{print $2}' | awk -F '/' '{print $NF}' | tr -d '[:space:]')

    echo creating user ${operation} with PW ${operation}12345
    curl -s  --fail "$HARBOR_BASE_URL/api/v2.0/users" -X POST -u admin:Harbor12345 -H 'Content-Type: application/json' --data-raw "{\"username\":\"$operation\",\"email\":\"$operation@example.com\",\"realname\":\"$operation example\",\"password\":\"${operation}12345\",\"comment\":null}"

    echo "Adding member ${operation} to project ${lower_operation}; ID=${projectId}"
    curl --fail "$HARBOR_BASE_URL/api/v2.0/projects/${projectId}/members" -X POST -u admin:Harbor12345 -H 'Content-Type: application/json' --data-raw "{\"role_id\":${roles['maintainer']},\"member_user\":{\"username\":\"$operation\"}}"
done

echo "creating user ${readOnlyUser} with PW ${readOnlyUser}12345"
curl -s  --fail "$HARBOR_BASE_URL/api/v2.0/users" -X POST -u admin:Harbor12345 -H 'Content-Type: application/json' --data-raw "{\"username\":\"$readOnlyUser\",\"email\":\"$readOnlyUser@example.com\",\"realname\":\"$readOnlyUser example\",\"password\":\"${readOnlyUser}12345\",\"comment\":null}"
echo "Adding member ${readOnlyUser} to project proxy; ID=${projectId}"
curl  --fail "$HARBOR_BASE_URL/api/v2.0/projects/${projectId}/members" -X POST -u admin:Harbor12345 -H 'Content-Type: application/json' --data-raw "{\"role_id\":${roles['limited-guest']},\"member_user\":{\"username\":\"${readOnlyUser}\"}}"


# When updating the container image versions note that all images of a chart are listed at artifact hub on the right hand side under "Containers Images"
skopeo copy $MAILHOG_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/mailhog
skopeo copy $ESO_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/external-secrets
skopeo copy $VAULT_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/vault
skopeo copy $NGINX_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/nginx
skopeo copy $TRAEFIK_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/traefik

# Monitoring
# Using latest will lead to failure with
# k describe prometheus -n monitoring
#  Message:               initializing PrometheusRules failed: failed to parse version: Invalid character(s) found in major number "0latest"
skopeo copy $PROMETHEUS_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/prometheus
skopeo copy $PROMETHEUS_OPERATOR_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/prometheus-operator
skopeo copy $PROMETHEUS_OPERATOR_CONFIG_RELOADER --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/prometheus-config-reloader
skopeo copy $GRAFANA_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/grafana
skopeo copy $K8S_SIDECAR --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/k8s-sidecar

# Cert Manager images
skopeo copy $CERT_MANAGER_CONTROLLER --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/cert-manager-controller
skopeo copy $CERT_MANAGER_CA_INJECTOR --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/cert-manager-cainjector
skopeo copy $CERT_MANAGER_WEBHOOK --dest-creds Proxy:Proxy12345 --dest-tls-verify=false $HARBOR_DOCKER_BASE_URL/proxy/cert-manager-webhook

# Needed for the builds to work with proxy-registry
skopeo copy $KUBECTL_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/bitnami/kubectl:1.29
skopeo copy $TEMURIN_IMAGE --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/eclipse-temurin:17-jre-alpine
skopeo copy $HELM_IMAGE  --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/helm:latest
skopeo copy $MVN_IMAGE  --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/maven:3-eclipse-temurin-17-alpine
skopeo copy $YAMLLINT_IMAGE  --dest-creds Proxy:Proxy12345 --dest-tls-verify=false  $HARBOR_DOCKER_BASE_URL/proxy/yamllint:latest
