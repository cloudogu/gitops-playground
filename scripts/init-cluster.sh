#!/usr/bin/env bash

# See https://github.com/rancher/k3d/releases
# This variable is also read in Jenkinsfile
K3D_VERSION=4.4.7
K8S_VERSION=1.21.2
K3S_VERSION="rancher/k3s:v${K8S_VERSION}-k3s1"
CLUSTER_NAME=gitops-playground
BIND_LOCALHOST=true

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"

if [[ -n "${DEBUG}" ]]; then set -x; fi

set -o errexit -o nounset -o pipefail

# Allow for running this script directly via curl without redundant code 
if [[ -f ${ABSOLUTE_BASEDIR}/utils.sh ]]; then
  source ${ABSOLUTE_BASEDIR}/utils.sh
else
  source <(curl -s https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/utils.sh)
fi

function main() {
  CLUSTER_NAME="$1"
  BIND_LOCALHOST="$2"

  # Install k3d if necessary
  if ! command -v k3d >/dev/null 2>&1; then
    installK3d
  else
    ACTUAL_K3D_VERSION="$(k3d --version | grep k3d | sed 's/k3d version v\(.*\)/\1/')"
    if [[ "${K3D_VERSION}" != "${ACTUAL_K3D_VERSION}" ]]; then
      msg="WARN: GitOps playground was tested with ${K3D_VERSION}. You are running k3d ${ACTUAL_K3D_VERSION}."
    fi
  fi

  createCluster
}

function installK3d() {
  curl -s https://raw.githubusercontent.com/rancher/k3d/main/install.sh | TAG=v${K3D_VERSION} bash
}

function createCluster() {
  echo "Initializing k3d-cluster '${CLUSTER_NAME}'"

  if k3d cluster list ${CLUSTER_NAME} >/dev/null 2>&1; then
    if confirm "Cluster '${CLUSTER_NAME}' already exists. Do you want to recreate the cluster?" ' [y/N]'; then
      k3d cluster delete ${CLUSTER_NAME}
    else
      echo "Not recreated."
      # Return error here to avoid possible subsequent commands to be executed
      exit 1
    fi
  fi

  K3D_ARGS=(
    # Allow services to bind to ports < 30000
    '--k3s-server-arg=--kube-apiserver-arg=service-node-port-range=8010-32767'
    # Used by Jenkins Agents pods
    '-v /var/run/docker.sock:/var/run/docker.sock@server[0]'
    # Allows for finding out the GID of the docker group in order to allow the Jenkins agents pod to access docker socket
    '-v /etc/group:/etc/group@server[0]'
    # Persists the cache of Jenkins agents pods for faster builds
    '-v /tmp:/tmp@server[0]'
    # Pin k8s version via k3s image
    "--image=$K3S_VERSION" 
  )

  if [[ ${BIND_LOCALHOST} == 'true' ]]; then
    K3D_ARGS+=(
      '--network=host'
    )
  fi

  k3d cluster create ${CLUSTER_NAME} ${K3D_ARGS[*]}

  echo "Adding k3d cluster to ~/.kube/config"
  k3d kubeconfig merge ${CLUSTER_NAME} --kubeconfig-switch-context > /dev/null
}

function printParameters() {
  echo "The following parameters are valid:"
  echo
  echo " -h | --help     >> Help screen"
  echo
  echo "Set your prefered cluster name to install k3d. Defaults to 'k8s-gitops-playground'."
  echo "    | --cluster-name=VALUE   >> Sets the cluster name."
}

COMMANDS=$(getopt \
  -o h \
  --long help,cluster-name:,bind-localhost: \
  -- "$@")

eval set -- "$COMMANDS"

while true; do
  case "$1" in
    -h | --help   )   printParameters; exit 0 ;;
    --cluster-name)   CLUSTER_NAME="$2"; shift 2 ;;
    --bind-localhost) BIND_LOCALHOST="$2"; shift 2 ;;
    --) shift; break ;;
  *) break ;;
  esac
done

main "$CLUSTER_NAME" "$BIND_LOCALHOST"
