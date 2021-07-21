#!/usr/bin/env bash

# set -o errexit -o nounset -o pipefail
# set -x

# See https://github.com/rancher/k3d/releases
# This variable is also read in Jenkinsfile
K3D_VERSION=4.4.4
K3D_CLUSTER_NAME=gitops-playground
CLUSTER_NAME=${K3D_CLUSTER_NAME}

BIND_LOCALHOST=true

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
source ${ABSOLUTE_BASEDIR}/utils.sh

if [[ -n "${DEBUG}" ]]; then set -x; fi

function main() {
  CLUSTER_NAME="$1"
  BIND_LOCALHOST="$2"
  SKIP_KUBECTL="$3"

  # Install k3d if necessary
  if ! command -v k3d >/dev/null 2>&1; then
    installK3d
  else
    ACTUAL_K3D_VERSION="$(k3d --version | grep k3d | sed 's/k3d version v\(.*\)/\1/')"
    echo "k3d ${ACTUAL_K3D_VERSION} already installed"
    if [[ "${K3D_VERSION}" != "${ACTUAL_K3D_VERSION}" ]]; then
      msg="Up-/downgrade from ${ACTUAL_K3D_VERSION} to ${K3D_VERSION}?"
      confirm "$msg" ' [y/n]' &&
        installK3d
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
      exit 0
    fi
  fi

  # if local setup is not disabled via env_var it is set to bind to localhost
  K3D_ARGS=(
    '--k3s-server-arg=--kube-apiserver-arg=service-node-port-range=8010-32767'
    # Used by Jenkins Agents pods
    '-v /var/run/docker.sock:/var/run/docker.sock@server[0]'
    '-v /tmp:/tmp@server[0]'
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

main "$CLUSTER_NAME" "$BIND_LOCALHOST" "$SKIP_KUBECTL"
