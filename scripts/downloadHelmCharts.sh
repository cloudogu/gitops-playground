#!/usr/bin/env bash
#execute from root folder
set -o errexit -o nounset -o pipefail
charts=( 'kube-prometheus-stack' 'external-secrets' 'vault' 'traefik' 'cert-manager' 'jenkins' 'scm-manager')
CONFIG="${1:-src/main/groovy/com/cloudogu/gitops/config/Config.groovy}"
SCM_MANAGER_CONFIG="src/main/groovy/com/cloudogu/gitops/config/scm/ScmTenantSchema.groovy"
CONFIG_FILES=("${CONFIG}")

if [[ "${CONFIG}" != "${SCM_MANAGER_CONFIG}" ]]; then
  CONFIG_FILES+=("${SCM_MANAGER_CONFIG}")
fi

tmpRepoFile="$(mktemp)"

mkdir -p charts

function extractChartProperty() {
  local chartDetails="$1"
  local property="$2"

  echo "$chartDetails" | sed -nE "s/.*${property}[[:space:]]*:[[:space:]]*'([^']+)'.*/\1/p" | head -n1
}

for chart in "${charts[@]}"; do
  chartDetails=""
  chartConfig=""
  for configFile in "${CONFIG_FILES[@]}"; do
    if [[ ! -f "${configFile}" ]]; then
      continue
    fi
    chartDetails=$(grep -m1 -EA5 "chart[[:space:]]*:[[:space:]]*'${chart}'" "${configFile}" || true)
    if [[ -n "$chartDetails" ]]; then
      chartConfig="${configFile}"
      break
    fi
  done

  if [[ -z "$chartDetails" ]]; then
    echo "Did not find chart details for chart $chart in files: ${CONFIG_FILES[*]}" >&2
    exit 1
  fi
  repo=$(extractChartProperty "$chartDetails" "repoURL")
  chart=$(extractChartProperty "$chartDetails" "chart")
  version=$(extractChartProperty "$chartDetails" "version")

  if [[ -z "$repo" || -z "$chart" || -z "$version" ]]; then
    echo "Could not extract chart details from ${chartConfig}: repoURL='${repo}', chart='${chart}', version='${version}'" >&2
    exit 1
  fi

  # avoid Error: failed to untar: a file or directory with the name charts/$chart already exists
  rm -rf "./charts/$chart"

  helm repo add "$chart" "$repo" --repository-config="${tmpRepoFile}"
  helm pull --untar --untardir ./charts "$chart/$chart" --version "$version" --repository-config="${tmpRepoFile}"
  # Note that keeping charts as tgx would need only 1/10 of storage
  # But untaring them in groovy would need additional libraries.
  # As layers of the image are compressed anyway, we'll do the untar process here, pragmatically

  # Do a simple verification
   helm template test "./charts/$chart" > /dev/null
done
