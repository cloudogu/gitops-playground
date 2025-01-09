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
    setExternalHostnameIfNecessary "JENKINS" "jenkins" "default"
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


initJenkins "$@"