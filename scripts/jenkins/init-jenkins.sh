#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
# set -x

if [[ -z ${PLAYGROUND_DIR+x} ]]; then
  BASEDIR=$(dirname $0)
  PLAYGROUND_DIR="$(cd "${BASEDIR}" && cd .. && cd .. && pwd)"
fi

# When Upgrading helm chart, also upgrade additionalPlugins in jenkins/values.yaml
# Check for "Failed to load" in the Jenkins log and add or upgrade the plugins mentioned there appropriately.
# In addition, check if the new helm chart version uses a newer agent.tag.
# Note that we need JDK11 on the agents, whereas the helm chart uses JDK8 by default.
JENKINS_HELM_CHART_VERSION=3.5.9

SET_USERNAME="admin"
SET_PASSWORD="admin"
REMOTE_CLUSTER=false

source "${PLAYGROUND_DIR}"/scripts/jenkins/jenkins-REST-client.sh

function deployLocalJenkins() {
  SET_USERNAME=${1}
  SET_PASSWORD=${2}
  REMOTE_CLUSTER=${3}
  JENKINS_URL=${4}

  # Mark the first node for Jenkins and agents. See jenkins/values.yamls "agent.workingDir" for details.
  # Remove first (in case new nodes were added)
  kubectl label --all nodes node- >/dev/null
  kubectl label $(kubectl get node -o name | sort | head -n 1) node=jenkins

  createSecret jenkins-credentials --from-literal=jenkins-admin-user=$SET_USERNAME --from-literal=jenkins-admin-password=$SET_PASSWORD -n default

  kubectl apply -f jenkins/resources || true

  helm upgrade -i jenkins --values jenkins/values.yaml \
    $(jenkinsHelmSettingsForLocalCluster) --set agent.runAsGroup=$(queryDockerGroupOfJenkinsNode) \
    --set controller.jenkinsUrl=$JENKINS_URL \
    --version ${JENKINS_HELM_CHART_VERSION} jenkins/jenkins -n default
}

function jenkinsHelmSettingsForLocalCluster() {
  if [[ $REMOTE_CLUSTER != true ]]; then
    # We need a host port, so jenkins can be reached via localhost:9090
    # But: This helm charts only uses the nodePort value, if the type is "NodePort". So change it for local cluster.
    echo "--set controller.serviceType=NodePort"
  fi
}

# using local cluster on k3d we grep local host gid for docker
function queryDockerGroupOfJenkinsNode() {
  kubectl apply -f jenkins/tmp-docker-gid-grepper.yaml >/dev/null
  until kubectl get po --field-selector=status.phase=Running | grep tmp-docker-gid-grepper >/dev/null; do
    sleep 1
  done

  kubectl exec tmp-docker-gid-grepper -- cat /etc/group | grep docker | cut -d: -f3

  # This call might block some (unnecessary) seconds so move to background
  kubectl delete -f jenkins/tmp-docker-gid-grepper.yaml >/dev/null &
}

function waitForJenkins() {
  echo -n "Waiting for Jenkins to become available at ${JENKINS_URL}/login"
  HTTP_CODE="0"
  while [[ "${HTTP_CODE}" -ne "200" ]]; do
    HTTP_CODE="$(curl -s -L -o /dev/null --max-time 10 -w ''%{http_code}'' "${JENKINS_URL}/login")" || true
    echo -n "."
    sleep 2
  done
  echo ""
}

function configureJenkins() {
  local SCMM_URL pluginFolder
  
  JENKINS_URL="${1}"
  export JENKINS_URL
  JENKINS_USERNAME="${2}"
  export JENKINS_USERNAME
  JENKINS_PASSWORD="${3}"
  export JENKINS_PASSWORD
  SCMM_URL="${4}"
  SCMM_PASSWORD="${5}"
  REGISTRY_URL="${6}"
  REGISTRY_PATH="${7}"
  REGISTRY_USERNAME="${8}"
  REGISTRY_PASSWORD="${9}"
  INSTALL_ALL_MODULES="${10}"
  INSTALL_FLUXV1="${11}"
  INSTALL_FLUXV2="${12}"
  INSTALL_ARGOCD="${13}"
  
  
  waitForJenkins

  pluginFolder=$(mktemp -d)
  "${PLAYGROUND_DIR}"/scripts/jenkins/plugins/download-plugins.sh "${pluginFolder}" 

  # Install all downloaded plugin files via HTTP
  for pluginFile in "${pluginFolder}/plugins"/*; do 
     installPlugin "${pluginFile}"
  done
 
  safeRestart
  waitForJenkins

  setGlobalProperty "SCMM_URL" "${SCMM_URL}"
  setGlobalProperty "REGISTRY_URL" "${REGISTRY_URL}"
  setGlobalProperty "REGISTRY_PATH" "${REGISTRY_PATH}"

  createCredentials "scmm-user" "gitops" "${SCMM_PASSWORD}" "credentials for accessing scm-manager"
  createCredentials "registry-user" "${REGISTRY_USERNAME}" "${REGISTRY_PASSWORD}" "credentials for accessing the docker-registry"


  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_FLUXV1 == true ]]; then
    createJob "fluxv1-applications" "${SCMM_URL}" "fluxv1" "scmm-user"
  fi
  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_FLUXV2 == true ]]; then
    createJob "fluxv2-applications" "${SCMM_URL}" "fluxv2" "scmm-user"
  fi
  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_ARGOCD == true ]]; then
    createJob "argocd-applications" "${SCMM_URL}" "argocd" "scmm-user"
  fi
}
