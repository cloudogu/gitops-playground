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
    setExternalHostnameIfNecessary "JENKINS" "jenkins" "${NAME_PREFIX}jenkins"
  fi

  installPlugins
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

function installPlugins() {
  if [[ "${SKIP_PLUGINS:-false}" == "true" ]]; then
    echo "Skipping Jenkins plugin installation due to SKIP_PLUGIN=true"
    return
  fi

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

  if [[ "${SKIP_RESTART:-false}" != "true" ]]; then
    safeRestart

    # we add a sleep here since there are issues directly after jenkins is available and getting 403 when curling jenkins
    # script executor. We think this might be a timing issue so we are waiting.
    # Since safeRestart can take time until it really restarts jenkins, we will sleep here before querying jenkins status.
    sleep 5
    waitForJenkins
  else
    echo "Skipping safeRestart due to SKIP_RESTART=true"
  fi
}

initJenkins "$@"