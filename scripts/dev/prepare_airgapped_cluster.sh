#!/bin/bash
set -xeu pipefail
# This script will setup a cluster to test an air-gapped environment with
# It copies all needed container images for an airgapped-environment-test into a harbor instance


# Add more images here, if you like
# We're not adding registry, scmm, jenkins and argocd here, because we have to install them before we go offline (see bellow for details).
IMAGE_PATTERNS=('external-secrets' \
  'vault' \
  'prometheus' \
  'grafana' \
  'sidecar' \
  'traefik')
BASIC_SRC_IMAGES=$(
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*]}{range .spec.containers[*]}{'\n'}{.image}{end}{end}" \
  | grep -Ff <(printf "%s\n" "${IMAGE_PATTERNS[@]}") \
  | sed 's/docker\.io\///g' | sort | uniq)
BASIC_DST_IMAGES=''

# create cluster
k3d cluster delete gitops-playground
./scripts/init-cluster.sh --cluster-name=airgapped-playground

K3D_NODE=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-airgapped-playground-server-0)

# Now init some apps you want to have running (e.g. harbor) before going airgapped
helm upgrade  -i my-harbor harbor/harbor --version 1.12.2 --namespace harbor -f ./scripts/dev/external-registry-values.yaml --set externalURL=http://$K3D_NODE:30002 --create-namespace

# waiting for harbor to come online
until curl -sf http://$K3D_NODE:30002 > /dev/null; do sleep 1; done


# Switch context to airgapped cluster here, e.g.
sed -i -r "s/0.0.0.0([^0-9]+[0-9]*|\$)/${K3D_NODE}:6443/g" ~/.config/k3d/kubeconfig-airgapped-playground.yaml
export KUBECONFIG=$HOME/.config/k3d/kubeconfig-airgapped-playground.yaml

while IFS= read -r image; do
  dstImage=$K3D_NODE:30002/library/${image##*/}
  echo pushing image $image to $dstImage
  skopeo copy docker://$image --dest-creds admin:Harbor12345 --dest-tls-verify=false  docker://$dstImage
  BASIC_DST_IMAGES+="${dstImage}\n"
done <<< "$BASIC_SRC_IMAGES"
echo $BASIC_DST_IMAGES
