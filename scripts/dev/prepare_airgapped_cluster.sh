#!/bin/bash
set -xeu pipefail
# This script will setup a cluster to test an air-gapped environment with
# It copies all needed container images for an airgapped-environment-test into a harbor instance

# create cluster
k3d cluster delete gitops-playground
./scripts/init-cluster.sh --cluster-name=airgapped-playground

K3D_NODE=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-airgapped-playground-server-0)

helm repo add harbor https://helm.goharbor.io
helm upgrade -i my-harbor harbor/harbor --version 1.14.2 --namespace harbor --create-namespace  --values ./scripts/dev/external-registry-values.yaml --set externalURL=http://$K3D_NODE:30002

# Switch context to airgapped cluster here, e.g.
sed -i -r "s/0.0.0.0([^0-9]+[0-9]*|\$)/${K3D_NODE}:6443/g" ~/.config/k3d/kubeconfig-airgapped-playground.yaml
export KUBECONFIG=$HOME/.config/k3d/kubeconfig-airgapped-playground.yaml

./scripts/dev/mirror_images_to_registry.sh http://$K3D_NODE:30002
