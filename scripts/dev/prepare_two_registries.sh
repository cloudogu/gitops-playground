#!/bin/bash
set -xeu pipefail
# This script will prepare a cluster so you can test GOP in an "two registries" scenario


scripts/init-cluster.sh
helm repo add harbor https://helm.goharbor.io
helm upgrade -i my-harbor harbor/harbor --version 1.14.2 --namespace harbor --create-namespace  --values ./scripts/dev/two-registries-values.yaml
./scripts/dev/mirror_images_to_registry.sh http://localhost:30000 configureHarbor

# Copy content of config.yaml from line one till the last list element under namespaces
awk '1; /example-apps-staging/ {exit}' ./examples/example-apps-via-content-loader/config.yaml > ./scripts/local/two-registries.yaml
# Append following lines to the config file file
cat <<EOF >> ./scripts/local/two-registries.yaml
  variables:
    petclinic:
      baseDomain: "petclinic.localhost"
    nginx:
      baseDomain: "nginx.localhost"
    images:
      kubectl: "localhost:30000/proxy/kubectl:1.29"
      helm: "localhost:30000/proxy/helm:3.16.4-1"
      kubeval: "localhost:30000/proxy/helm:3.16.4-1"
      helmKubeval: "localhost:30000/proxy/helm:3.16.4-1"
      yamllint: "localhost:30000/proxy/cytopia/yamllint:1.25-0.7"
      nginx: ""
      petclinic: "localhost:30000/proxy/eclipse-temurin:17-jre-alpine"
      maven: "localhost:30000/proxy/maven:3-eclipse-temurin-17-alpine"
jenkins:
  active: true
application:
  baseUrl: "http://localhost"
  insecure: true
features:
  argocd:
    active: true
  monitoring:
    active: true
    helm:
      grafanaImage: "localhost:30000/proxy/grafana"
      grafanaSidecarImage: "localhost:30000/proxy/k8s-sidecar"
      prometheusImage: "localhost:30000/proxy/prometheus"
      prometheusOperatorImage: "localhost:30000/proxy/prometheus-operator"
      prometheusConfigReloaderImage: "localhost:30000/proxy/prometheus-config-reloader"
  ingress:
    active: true
    helm:
      values:
        image:
          registry: "localhost:30000"
          repository: "traefik/traefik"
          tag: "v3.3.3"
  secrets:
    externalSecrets:
      helm:
        image: "localhost:30000/proxy/external-secrets"
    vault:
      helm:
        image: "localhost:30000/proxy/vault"
  certManager:
    helm:
      image: "localhost:30000/proxy/cert-manager-controller"
      webhookImage: "localhost:30000/proxy/cert-manager-webhook"
      cainjectorImage: "localhost:30000/proxy/cert-manager-cainjector"

EOF
