#!/usr/bin/env bash

# See https://github.com/rancher/k3d/releases
# This variable is also read in Jenkinsfile
K3D_VERSION=4.4.7
# When updating please also adapt k8s-related versions in Dockerfile, vars.tf and apply.sh
K8S_VERSION=1.21.2
K3S_VERSION="rancher/k3s:v${K8S_VERSION}-k3s1"

set -o errexit -o nounset -o pipefail

function main() {
  readParameters "$@"
  
  [[ $TRACE == true ]] && set -x;
  
  # Install k3d if necessary
  if ! command -v k3d >/dev/null 2>&1; then
    installK3d
  else
    ACTUAL_K3D_VERSION="$(k3d --version | grep k3d | sed 's/k3d version v\(.*\)/\1/')"
    if [[ "${K3D_VERSION}" != "${ACTUAL_K3D_VERSION}" ]]; then
      echo "WARN: GitOps playground was tested with ${K3D_VERSION}. You are running k3d ${ACTUAL_K3D_VERSION}."
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
      echo "Deleting cluster ${CLUSTER_NAME}"
      k3d cluster delete ${CLUSTER_NAME} >/dev/null 2>&1;
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
    # Disable traefik (no ingresses used so far)
    '--k3s-server-arg=--disable=traefik' 
    # Disable servicelb (avoids "Pending" svclb pods and we use nodePorts right now anyway)
    '--k3s-server-arg=--disable=servicelb' 
    # Pin k8s version via k3s image
    "--image=$K3S_VERSION" 
  )

  local isUsingArbitraryRegistryPort=false
  if [[ ${BIND_LOCALHOST} == 'true' ]]; then
    K3D_ARGS+=(
      '--network=host'
    )
  else
    # Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on docker push 
    # in the example application's Jenkins Jobs.
    # If available, use default port for playground registry, because no parameter is required when applying
    if command -v netstat >/dev/null 2>&1 && ! netstat -an | grep 30000 | grep LISTEN >/dev/null 2>&1; then
      K3D_ARGS+=(
       '-p 30000:30000@server[0]'
      )
    else
      # If default port is in use, choose an arbitrary port.
      # The port must then be passed when applying the playground as --internal-registry-port (printed after creation)
      isUsingArbitraryRegistryPort=true
      K3D_ARGS+=(
       '-p 30000@server[0]'
      )
    fi
  fi

  echo "Creating cluster ${CLUSTER_NAME}"
  k3d cluster create ${CLUSTER_NAME} ${K3D_ARGS[*]} >/dev/null
  
  if [[ ${isUsingArbitraryRegistryPort} == 'true' ]]; then
    local registryPort
    registryPort=$(docker inspect \
      --format='{{ with (index .NetworkSettings.Ports "30000/tcp") }}{{ (index . 0).HostPort }}{{ end }}' \
       k3d-${CLUSTER_NAME}-server-0)
    echo "Bound internal registry port 30000 to free localhost port ${registryPort}."
    echo "Make sure to pass --internal-registry-port=${registryPort} when applying the playground."
  fi

  echo "Adding k3d cluster to ~/.kube/config"
  k3d kubeconfig merge ${CLUSTER_NAME} --kubeconfig-switch-context > /dev/null
}

function printParameters() {
  echo "The following parameters are valid:"
  echo
  echo " -h | --help     >> Help screen"
  echo
  echo "Set your prefered cluster name to install k3d. Defaults to 'gitops-playground'."
  echo "    | --cluster-name=VALUE   >> Sets the cluster name."
}

function confirm() {
  # shellcheck disable=SC2145
  # - the line break between args is intended here!
  printf "%s\n" "${@:-Are you sure? [y/N]} "
  
  read -r response
  case "$response" in
  [yY][eE][sS] | [yY])
    true
    ;;
  *)
    false
    ;;
  esac
}

readParameters() {
  COMMANDS=$(getopt \
    -o hx \
    --long help,cluster-name:,bind-localhost:,trace \
    -- "$@")
  
  eval set -- "$COMMANDS"
  
  CLUSTER_NAME=gitops-playground
  BIND_LOCALHOST=true
  TRACE=false

  while true; do
    case "$1" in
      -h | --help   )   printParameters; exit 0 ;;
      --cluster-name)   CLUSTER_NAME="$2"; shift 2 ;;
      --bind-localhost) BIND_LOCALHOST="$2"; shift 2 ;;
      -x | --trace    ) TRACE=true; shift ;;
      --) shift; break ;;
    *) break ;;
    esac
  done
}

main "$@"
