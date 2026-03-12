#!/bin/bash
set -xeu pipefail
# This script will prepare a cluster so you can test GOP in an "two registries" scenario


scripts/init-cluster.sh
helm repo add harbor https://helm.goharbor.io
helm upgrade -i my-harbor harbor/harbor --version 1.14.2 --namespace harbor --create-namespace  --values ./scripts/dev/two-registries-values.yaml
./scripts/dev/mirror_images_to_registry.sh

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
EOF
