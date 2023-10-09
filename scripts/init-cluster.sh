#!/usr/bin/env bash

# See https://github.com/rancher/k3d/releases
# This variable is also read in Jenkinsfile
K3D_VERSION=5.6.0
# When updating please also adapt in Dockerfile, vars.tf, ApplicationConfigurator.groovy and apply.sh
K8S_VERSION=1.25.5
K3S_VERSION="rancher/k3s:v${K8S_VERSION}-k3s2"

set -o errexit -o nounset -o pipefail

function main() {
  readParameters "$@"
  
  [[ $TRACE == true ]] && set -x;
  
  # Install k3d if necessary
  if ! command -v k3d >/dev/null 2>&1; then
    echo The GitOps playground uses k3d, which is not found on the PATH. 
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
    echo "Installing install k3d ${K3D_VERSION} to \$HOME/.local/bin"
    echo 'Using this script: https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh'
    # shellcheck disable=SC2016
    echo 'If $HOME/.local/bin is not on your PATH, you can add it for this session: export PATH="$HOME/.local/bin:$PATH"'
    # shellcheck disable=SC2016
    echo 'You can uninstall k3d later via: rm $HOME/.local/bin/k3d'
    if confirm "Do you want to continue?" ' [y/N]'; then
      # Allow this script to execute k3d without having /.local/bin on the path
      export PATH="$HOME/.local/bin:$PATH"
      mkdir -p .local/bin
      curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | \
        TAG=v${K3D_VERSION} K3D_INSTALL_DIR=${HOME}/.local/bin bash -s -- --no-sudo
    else
      echo "Not installed."
      # Return error here to avoid possible subsequent commands to be executed
      exit 1
    fi
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

  HOST_PORT_RANGE='8010-65535'
  K3D_ARGS=(
    # Allow services to bind to ports < 30000 > 32xxx
    "--k3s-arg=--kube-apiserver-arg=service-node-port-range=${HOST_PORT_RANGE}@server:0"
    # TODO param for setting port?
    # TODO or error handling when port in use -> let docker bind to arbitrary port
    # TODO Default to other than 8080 to have less problems -> But longer urls scmm.localhost:8080 instead of plain scmm.localhost
    # Note that 127.0.0.1:80 would be more secure, but then requests to localhost fail
    '-p 8080:80@server:0:direct'
    # Used by Jenkins Agents pods
    '-v /var/run/docker.sock:/var/run/docker.sock@server:0'
    # Allows for finding out the GID of the docker group in order to allow the Jenkins agents pod to access docker socket
    '-v /etc/group:/etc/group@server:0'
    # Persists the cache of Jenkins agents pods for faster builds
    '-v /tmp:/tmp@server:0'
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
       '-p 30000:30000@server:0:direct'
      )
    else
      # If default port is in use, choose an arbitrary port.
      # The port must then be passed when applying the playground as --internal-registry-port (printed after creation)
      isUsingArbitraryRegistryPort=true
      K3D_ARGS+=(
       '-p 30000@server:0:direct'
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

  # Write ~/.config/k3d/kubeconfig-${CLUSTER_NAME}.yaml
  # https://k3d.io/v5.6.0/usage/kubeconfig/
  # Using this file makes applying the playground from docker more reliable and secure
  # Otherwise a change of the current kubecontext (e.g. via kubectx) in the default kubeconfig will lead to the playground being applied to the wrong cluster
  k3d kubeconfig write ${CLUSTER_NAME} > /dev/null
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
  BIND_LOCALHOST=false
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
