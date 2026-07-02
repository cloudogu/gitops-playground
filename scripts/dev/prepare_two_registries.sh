#!/bin/bash
set -xeu pipefail
# This script will prepare a cluster so you can test GOP in an "two registries" scenario


scripts/init-cluster.sh
helm repo add harbor https://helm.goharbor.io
helm upgrade -i my-harbor harbor/harbor --version 1.14.2 --namespace harbor --create-namespace  --values ./scripts/dev/two-registries-values.yaml
./scripts/dev/mirror_images_to_registry.sh http://localhost:30000 configureHarbor

# Append following lines to the config file file
cat <<EOF > ./scripts/local/two-registries.yaml
content:
  repos:
    - url: https://github.com/cloudogu/gitops-build-lib
      target: 3rd-party-dependencies/gitops-build-lib
      overwriteMode: RESET
    - url: https://github.com/cloudogu/ces-build-lib
      target: 3rd-party-dependencies/ces-build-lib
      overwriteMode: RESET
    - url: https://github.com/cloudogu/spring-boot-helm-chart
      target: 3rd-party-dependencies/spring-boot-helm-chart
      overwriteMode: RESET
    - url: https://github.com/cloudogu/spring-petclinic
      target: argocd/petclinic-plain
      ref: feature/gitops_ready
      targetRef: main
      overwriteMode: UPGRADE
      createJenkinsJob: true
    - url: https://github.com/cloudogu/spring-petclinic
      target: argocd/petclinic-helm
      ref: feature/gitops_ready
      targetRef: main
      overwriteMode: UPGRADE
      createJenkinsJob: true
    - url: https://github.com/cloudogu/gitops-examples
      path: example-apps-via-content-loader/
      ref: main
      templating: true
      type: FOLDER_BASED
      overwriteMode: UPGRADE

  namespaces:
    - \${config.application.namePrefix}example-apps-production
    - \${config.application.namePrefix}example-apps-staging
  variables:
    petclinic:
      baseDomain: "petclinic"
    images:
      kubectl: "localhost:30000/proxy/kubectl:latest"
      helm: "localhost:30000/proxy/helm:latest"
      kubeval: "localhost:30000/proxy/helm:latest"
      helmKubeval: "localhost:30000/proxy/helm:latest"
      yamllint: "localhost:30000/proxy/cytopia/yamllint:latest"
      petclinic: "localhost:30000/proxy/eclipse-temurin:17-jre-alpine"
      maven: "localhost:30000/proxy/maven:3-eclipse-temurin-17-alpine"
registry:
  internalPort: 30000
  url: "localhost:30000"
  path: "registry"
  username: "Registry"
  password: "Registry12345"
  proxyUrl: "localhost:30000"
  proxyUsername: "Proxy"
  proxyPassword: "Proxy12345"
  readOnlyUsername: "RegistryRead"
  readOnlyPassword: "RegistryRead12345"
  createImagePullSecrets: true
jenkins:
  active: true
  jenkinsImage: "localhost:30000/proxy/jenkins-helm:latest"
scm:
  scmManager:
    scmmImage: "localhost:30000/proxy/scm-manager:latest"
application:
  baseUrl: "http://localhost"
  insecure: true
  yes: true
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
          repository: "proxy/traefik"
          tag: "v3.3.3"
  secrets:
    externalSecrets:
      helm:
        image: "localhost:30000/proxy/external-secrets"
    vault:
      helm:
        image: "localhost:30000/proxy/vault"
  certManager:
    active: true
    helm:
      image: "localhost:30000/proxy/cert-manager-controller"
      webhookImage: "localhost:30000/proxy/cert-manager-webhook"
      cainjectorImage: "localhost:30000/proxy/cert-manager-cainjector"

EOF
