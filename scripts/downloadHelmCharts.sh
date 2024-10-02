#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
charts=( 'kube-prometheus-stack' 'external-secrets' 'vault' 'mailhog' 'ingress-nginx')
CONFIG="${1:-src/main/groovy/com/cloudogu/gitops/config/schema/Schema.groovy}"

tmpRepoFile="$(mktemp)"

mkdir -p charts

for chart in "${charts[@]}"; do
  chartDetails=$(grep -m1 -EA3 "chart.*:.*${chart}" "${CONFIG}")
  
  repo=$(echo "$chartDetails"  | grep -oP "repoURL\s*:\s*'\K[^']+")
  chart=$(echo "$chartDetails" | grep -oP "chart\s*:\s*'\K[^']+") 
  version=$(echo "$chartDetails" | grep -oP "version\s*:\s*'\K[^']+")
  
  helm repo add "$chart" "$repo" --repository-config="${tmpRepoFile}"
  helm pull --untar --untardir ./charts "$chart/$chart" --version "$version" --repository-config="${tmpRepoFile}"
  # Note that keeping charts as tgx would need only 1/10 of storage
  # But untaring them in groovy would need additional libraries.
  # As layers of the image are compressed anyway, we'll do the untar process here, pragmatically
  
  # Do a simple verification
   helm template test "./charts/$chart" > /dev/null
done