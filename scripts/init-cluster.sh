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
      echoHightlighted "WARNING: GitOps playground was tested with ${K3D_VERSION}. You are running k3d ${ACTUAL_K3D_VERSION}."
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
    # Used by Jenkins Agents pods
    '-v /var/run/docker.sock:/var/run/docker.sock@server:0'
    # Allows for finding out the GID of the docker group in order to allow the Jenkins agents pod to access docker socket
    '-v /etc/group:/etc/group@server:0'
    # Persists the cache of Jenkins agents pods for faster builds
    '-v /tmp:/tmp@server:0'
    # Pin k8s version via k3s image
    "--image=$K3S_VERSION" 
  )

  if [[ ${BIND_LOCALHOST} == 'true' ]]; then
    K3D_ARGS+=(
      '--network=host'
    )
  else
    
    if [[ "${BIND_REGISTRY_PORT}" != '0' ]]; then
      
      # Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on docker push 
      # in the example application's Jenkins Jobs.
      K3D_ARGS+=(
        "-p ${BIND_REGISTRY_PORT}:30000@server:0:direct"
      )
    else
      # User wants us to choose an arbitrary port.
      # The port must then be passed when applying the playground as --internal-registry-port (printed after creation)
      K3D_ARGS+=(
       '-p 30000@server:0:direct'
      )
    fi
    
    # Bind ingress port only when requested by parameter. 
    # On linux the pods can be reached without ingress via the k3d container's network address and the node port. 
    if [[ -n "${BIND_INGRESS_PORT}" ]]; then
        # Note that 127.0.0.1:$BIND_INGRESS_PORT would be more secure, but then requests to localhost fail
        K3D_ARGS+=(
            "-p ${BIND_INGRESS_PORT}:80@server:0:direct"
            )
    fi
  fi

  echo "Creating cluster '${CLUSTER_NAME}'"
  k3d cluster create ${CLUSTER_NAME} ${K3D_ARGS[*]} >/dev/null
  
  if [[ ${BIND_REGISTRY_PORT} != '30000' ]]; then
    local registryPort
    registryPort=$(docker inspect \
      --format='{{ with (index .NetworkSettings.Ports "30000/tcp") }}{{ (index . 0).HostPort }}{{ end }}' \
       k3d-${CLUSTER_NAME}-server-0)
    echo "Bound internal registry port 30000 to localhost port ${registryPort}."
    echoHightlighted "Make sure to pass --internal-registry-port=${registryPort} when applying the playground."
  fi
  
  if [[ -n "${BIND_INGRESS_PORT}" ]]; then
    echo "Bound ingress port to localhost:${BIND_INGRESS_PORT}."
    echoHightlighted "Make sure to pass a base-url, e.g. --base-url=http://127.0.0.1.sslip.io:${BIND_INGRESS_PORT} when applying the playground."
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
  echo "    | --cluster-name=STRING   >> Set your preferred cluster name to install k3d. Defaults to 'gitops-playground'."
  
  echo "    | --bind-localhost=BOOLEAN   >> Bind the k3d container to host network. Exposes all k8s nodePorts to localhost. Defaults to true."
  echo "    | --bind-ingress-port=INT   >> Bind the ingress controller to this localhost port. Sets --bind-localhost=false. Defaults to empty."
  echo "    | --bind-registry-port=INT   >> Specify a custom port for the container registry to bind to localhost port. Only use this when port 30000 is blocked and --bind-localhost=true. Defaults to 30000 (default used by the playground)."
  echo
  echo " -x | --trace         >> Debug + Show each command executed (set -x)"
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
    --long help,cluster-name:,bind-localhost:,bind-ingress-port:,bind-registry-port:,trace \
    -- "$@")
  
  eval set -- "$COMMANDS"
  
  CLUSTER_NAME=gitops-playground
  BIND_LOCALHOST=false
  BIND_INGRESS_PORT=""
  # Use default port for playground registry, because no parameter is required when applying
  BIND_REGISTRY_PORT="30000"
  TRACE=false

  while true; do
    case "$1" in
      -h | --help   )   printParameters; exit 0 ;;
      --cluster-name)   CLUSTER_NAME="$2"; shift 2 ;;
      --bind-localhost) BIND_LOCALHOST="$2"; shift 2 ;;
      --bind-ingress-port) BIND_INGRESS_PORT="$2"; shift 2 ;;
      --bind-registry-port) BIND_REGISTRY_PORT="$2"; shift 2 ;;
      -x | --trace    ) TRACE=true; shift ;;
      --) shift; break ;;
    *) break ;;
    esac
  done
  
  # bind-ingress-port takes precedence over bind-localhost  
  if [[ -n "${BIND_INGRESS_PORT}" ]]; then
    BIND_LOCALHOST=false
  fi
}

function echoHightlighted() {
    # Print to stdout in green
    echo -e "\e[32m$@\e[0m"
}

main "$@"
