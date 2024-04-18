#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

ABSOLUTE_BASEDIR="$(cd "$(dirname $0)" && pwd)"

source ${ABSOLUTE_BASEDIR}/../utils.sh
source ${ABSOLUTE_BASEDIR}/jenkins-REST-client.sh

if [[ $TRACE == true ]]; then
  set -x
fi

PLAYGROUND_DIR="$(cd ${ABSOLUTE_BASEDIR} && cd ../.. && pwd)"

JENKINS_PLUGIN_FOLDER=${JENKINS_PLUGIN_FOLDER:-''}

if [[ $INSECURE == true ]]; then
  CURL_HOME="${PLAYGROUND_DIR}"
  export CURL_HOME
fi

function initJenkins() {
  if [[ ${INTERNAL_JENKINS} == true ]]; then
    deployLocalJenkins 

    setExternalHostnameIfNecessary "JENKINS" "jenkins" "default"
  fi

  configureJenkins
}

function deployLocalJenkins() {

  # Mark the first node for Jenkins and agents. See jenkins/values.yamls "agent.workingDir" for details.
  # Remove first (in case new nodes were added)
  kubectl label --all nodes node- >/dev/null
  kubectl label $(kubectl get node -o name | sort | head -n 1) node=jenkins

  createSecret jenkins-credentials --from-literal=jenkins-admin-user=$JENKINS_USERNAME --from-literal=jenkins-admin-password=$JENKINS_PASSWORD -n default

  helm repo add jenkins https://charts.jenkins.io
  helm repo update jenkins
  helm upgrade -i jenkins --values jenkins/values.yaml \
    $(jenkinsHelmSettingsForLocalCluster) $(jenkinsIngress) $(setAgentGidOrUid) \
    --version ${JENKINS_HELM_CHART_VERSION} jenkins/jenkins -n default
}

function jenkinsIngress() {
  
    if [[ -n "${BASE_URL}" ]]; then
      if [[ $URL_SEPARATOR_HYPHEN == true ]]; then
        local jenkinsHost="jenkins-$(extractHost "${BASE_URL}")"
      else
        local jenkinsHost="jenkins.$(extractHost "${BASE_URL}")"
      fi
      local externalJenkinsUrl="$(injectSubdomain "${BASE_URL}" 'jenkins')"
      echo "--set controller.jenkinsUrl=$JENKINS_URL --set controller.ingress.enabled=true --set controller.ingress.hostName=${jenkinsHost}"
    else
      echo "--set controller.jenkinsUrl=$JENKINS_URL" 
    fi
}

function jenkinsHelmSettingsForLocalCluster() {
  if [[ $REMOTE_CLUSTER != true ]]; then
    # We need a host port, so jenkins can be reached via localhost:9090
    # But: This helm charts only uses the nodePort value, if the type is "NodePort". So change it for local cluster.
    echo "--set controller.serviceType=NodePort"
  fi
}

# Enable access for the Jenkins Agents Pods to the docker socket  
function setAgentGidOrUid() {
  # Try to find out the group ID (GID) of the docker group
  kubectl apply -f jenkins/tmp-docker-gid-grepper.yaml >/dev/null
  until kubectl get po --field-selector=status.phase=Running | grep tmp-docker-gid-grepper >/dev/null; do
    sleep 1
  done

  local DOCKER_GID=$(kubectl exec tmp-docker-gid-grepper -- cat /etc/group | grep docker | cut -d: -f3)
  if [[ -n "${DOCKER_GID}" ]]; then
    echo "--set agent.runAsGroup=$DOCKER_GID"
  else
    # If the docker group cannot be found, run as root user
    # Unfortunately, the root group (GID 0) usually does not have access to the docker socket. Last ressort: run as root.
    # This will happen on Docker Desktop for Windows for example
    error "Warning: Unable to determine Docker Group ID (GID). Jenkins Agent pods will run as root user (UID 0)!"
    echo '--set agent.runAsUser=0'
  fi
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
  local pluginFolder

  waitForJenkins

  if [[ -z "${JENKINS_PLUGIN_FOLDER}" ]]; then
    pluginFolder=$(mktemp -d)
    echo "Downloading jenkins plugins to ${pluginFolder}"
    "${PLAYGROUND_DIR}"/scripts/jenkins/plugins/download-plugins.sh "${pluginFolder}"
  else
    echo "Jenkins plugins folder present, skipping plugin download"
    pluginFolder="${JENKINS_PLUGIN_FOLDER}"
  fi 

  echo "Installing Jenkins Plugins from ${pluginFolder}"
  awk -F':' '{ print $1 }' scripts/jenkins/plugins/plugins.txt | while read -r pluginName; do
     installPlugin "${pluginFolder}/plugins/${pluginName}.jpi"
  done

  echo "Waiting for plugin installation.."
  PLUGIN_STATUS=($(checkPluginStatus $(cat "${PLAYGROUND_DIR}"/scripts/jenkins/plugins/plugins.txt | tr '\n' ',')))
  while [[ ${#PLUGIN_STATUS[@]} -gt 0 ]]; do
    PLUGIN_STATUS=($(checkPluginStatus $(cat "${PLAYGROUND_DIR}"/scripts/jenkins/plugins/plugins.txt | tr '\n' ',')))
    echo "Processing: ${PLUGIN_STATUS[*]}"
    sleep 5
  done
  echo ""

  safeRestart

  # we add a sleep here since there are issues directly after jenkins is available and getting 403 when curling jenkins
  # script executor. We think this might be a timing issue so we are waiting.
  # Since safeRestart can take time until it really restarts jenkins, we will sleep here before querying jenkins status.
  sleep 5
  waitForJenkins
  
    if [[ $INSTALL_ARGOCD == true ]]; then
      createJob "${NAME_PREFIX}example-apps" "${SCMM_URL}" "${NAME_PREFIX}argocd" "scmm-user"
    fi
}

initJenkins "$@"