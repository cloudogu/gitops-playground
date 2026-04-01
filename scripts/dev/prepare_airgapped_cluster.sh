#!/bin/bash
set -xeu pipefail
# This script will setup a cluster to test an air-gapped environment with
# It copies all needed container images for an airgapped-environment-test into a harbor instance
# ./scripts/init-cluster.sh --cluster-name=airgapped-playground --registry-use k3d-airgappedreg:5000

AIRGAPPED_REGISTRY_NAME="agreg"
AIRGAPPED_REGISTRY_PORT=5000
AIRGAPPED_CLUSTER_NAME="airgapped-playground"

# create cluster
k3d cluster delete gitops-playground
k3d registry create $AIRGAPPED_REGISTRY_NAME --port $AIRGAPPED_REGISTRY_PORT
k3d cluster create $AIRGAPPED_CLUSTER_NAME --registry-use $AIRGAPPED_REGISTRY_NAME:$AIRGAPPED_REGISTRY_PORT --k3s-arg=--disable=traefik@server:*
k3d kubeconfig write $AIRGAPPED_CLUSTER_NAME > /dev/null

K3D_NODE=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-airgapped-playground-server-0)
AIRGAPPED_REGISTRY_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{println}}{{end}}' k3d-$AIRGAPPED_REGISTRY_NAME | head -1)

# Switch context to airgapped cluster here, e.g.
sed -i -r "s/127.0.0.1([^0-9]+[0-9]*|\$)/${K3D_NODE}:6443/g" ~/.config/k3d/kubeconfig-airgapped-playground.yaml
sed -r "s/<address>/k3d-$AIRGAPPED_REGISTRY_NAME:$AIRGAPPED_REGISTRY_PORT/g" ./scripts/dev/gop_airgapped_config.yaml.tpl > ./scripts/dev/gop_airgapped_config.yaml
export KUBECONFIG=$HOME/.config/k3d/kubeconfig-airgapped-playground.yaml

./scripts/dev/mirror_images_to_registry.sh http://${AIRGAPPED_REGISTRY_IP}:${AIRGAPPED_REGISTRY_PORT}
