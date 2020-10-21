#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

# See https://github.com/rancher/k3s/releases
# Note 1.16 is the oldest version available
# In order to match our older production version, we need to activate deprecated APIs (see bellow)
K3S_VERSION=1.18.8+k3s1
K3S_CLUSTER_NAME=k8s-gitops-playground

function main() {
  # Install  if necessary
  if command -v k3s >/dev/null 2>&1; then
    ACTUAL_K3S_VERSION="$(k3s --version | grep k3s | sed 's/k3s version v\(.*\) (.*/\1/')"
    echo "k3s ${ACTUAL_K3S_VERSION} already installed"
    if [[ "${K3S_VERSION}" != "${ACTUAL_K3S_VERSION}" ]]; then
      msg="Up-/downgrade from ${ACTUAL_K3S_VERSION} to ${K3S_VERSION}?"
    else
      msg="Reinstall?"
    fi
    confirm "$msg" 'Note: Applications will not be deleted. For deleting call "k3s-uninstall.sh" in advance.' ' [y/n]' \
      && installK3s

  else
    installK3s
  fi
}

function installK3s() {
  # More info:
  # * On Commands: https://k3s.io/usage/commands/
  # * On exposing services: https://github.com/rancher/k3s/blob/v3.0.1/docs/usage/guides/exposing_services.md
  K3S_ARGS=(# Use local docker daemon, so local images can be used within cluster.
    '--docker'
    # Enable APIs deprecated in K8s 1.16
    '--kube-apiserver-arg=runtime-config=apps/v1beta1=true,apps/v1beta2=true,extensions/v1beta1/daemonsets=true,extensions/v1beta1/deployments=true,extensions/v1beta1/replicasets=true,extensions/v1beta1/networkpolicies=true,extensions/v1beta1/podsecuritypolicies=true'
    # Allow for using myCloudogu's node ports
    '--kube-apiserver-arg=service-node-port-range=8010-32767'
    # Allow accessing KUBECONFIG as non-root
    '--write-kubeconfig-mode=755'
    # Save some resources
    '--no-deploy=traefik'
    '--no-deploy=metrics-server'
  )

  echo "Installing and starting k3s cluster (${K3S_VERSION}) via k3s."
  echo "To stop the cluster and all workloads use: k3s-killall.sh && docker ps -qf 'name=k8s_*' | xargs -I{} docker rm -f {}"
  echo "To restart the cluster use: sudo systemctl start k3s"
  echo "To uninstall the cluster use: k3s-uninstall.sh"
  echo
  curl -sfL https://get.k3s.io |
    INSTALL_K3S_VERSION="v${K3S_VERSION}" \
      INSTALL_K3S_EXEC="${K3S_ARGS[*]}" \
      sh -s -

  # Add kubeconfig, after renaming it to not be called "default"
  # Renaming via k3s is not possible https://github.com/rancher/k3s/issues/1806
  tmpConfig=$(mktemp)
  sed </etc/rancher/k3s/k3s.yaml "s/: default/: ${K3S_CLUSTER_NAME}/" >"$tmpConfig"
  KUBECONFIG=${tmpConfig}:~/.kube/config kubectl config view --flatten >~/.kube/config2 && mv ~/.kube/config2 ~/.kube/config

}

confirm() {
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

main "$@"
