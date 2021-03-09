#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

function authenticate() {
  CRUMB=$(curl -s --cookie-jar /tmp/cookies \
               -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" \
               "${JENKINS_URL}/crumbIssuer/api/json" | jq -r '.crumb')

  TOKEN=$(curl -s -X POST -H "Jenkins-Crumb:${CRUMB}" --cookie /tmp/cookies \
          "${JENKINS_URL}/me/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken\?newTokenName\=\foo" \
          -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" | jq -r '.data.tokenValue')

  echo "${TOKEN}"
}

function createJob() {
  JOB_NAME=${1}

  JOB_CONFIG=$(env -i \
               SCMM_NAMESPACE_JOB_SERVER_URL="${2}" \
               SCMM_NAMESPACE_JOB_NAMESPACE="${3}" \
               SCMM_NAMESPACE_JOB_CREDENTIALS_ID="${4}" \
               envsubst '${SCMM_NAMESPACE_JOB_SERVER_URL},
                         ${SCMM_NAMESPACE_JOB_NAMESPACE},
                         ${SCMM_NAMESPACE_JOB_CREDENTIALS_ID}' \
               < scripts/jenkins/namespaceJobTemplate.xml)

  printf "Creating job '${JOB_NAME}' ... "

  STATUS=$(curl -s -o /dev/null -X POST "${JENKINS_URL}/createItem?name=${JOB_NAME}" \
           -u "${JENKINS_USERNAME}:${TOKEN}" \
           -H "Content-Type:text/xml" \
           --data "${JOB_CONFIG}" \
           --write-out '%{http_code}')

  printStatus $STATUS
}

function createCredentials() {
  printf "Creating credentials for ${1} ... "

  CRED_CONFIG=$(env -i CREDENTIALS_ID="${1}" \
               USERNAME="${2}" \
               PASSWORD="${3}" \
               DESCRIPTION="${4}" \
               envsubst '${CREDENTIALS_ID},
                         ${USERNAME},
                         ${PASSWORD},
                         ${DESCRIPTION}' \
               < scripts/jenkins/credentialsTemplate.json)

  STATUS=$(curl -s -X POST "${JENKINS_URL}/credentials/store/system/domain/_/createCredentials" \
          -u "${JENKINS_USERNAME}:${TOKEN}" \
          --data-urlencode "json=${CRED_CONFIG}" \
          --write-out '%{http_code}')

  printStatus "${STATUS}"
}

function installPlugin() {
  PLUGIN_NAME=${1}
  PLUGIN_VERSION=${2}

  printf "Installing plugin ${PLUGIN_NAME} v${PLUGIN_VERSION} ..."

  STATUS=$(curl -s -o /dev/null -X POST "${JENKINS_URL}/pluginManager/installNecessaryPlugins" \
          -u "${JENKINS_USERNAME}:${TOKEN}" \
          -d '<jenkins><install plugin="'${PLUGIN_NAME}'@'${PLUGIN_VERSION}'"/></jenkins>' \
          -H 'Content-Type: text/xml' --write-out '%{http_code}')

  if [ ${STATUS} -eq 200 ] || [ ${STATUS} -eq 302 ]
  then
    waitForPluginInstallation "${PLUGIN_NAME}"
  fi
  printStatus "${STATUS}"
}

function safeRestart() {
  curl -X POST "${JENKINS_URL}/safeRestart" -u "${JENKINS_USERNAME}:${TOKEN}"
}

function printStatus() {
  STATUS_CODE=${1}
  if [ ${STATUS_CODE} -eq 200 ] || [ ${STATUS_CODE} -eq 302 ]
  then
    echo -e ' \u2705'
  else
    echo -e ' \u274c'
  fi
}

function waitForPluginInstallation() {
  PLUGIN_NAME=${1}
  while [[ $(curl -s -k "${JENKINS_URL}/pluginManager/api/json?depth=1" \
            -u "${JENKINS_USERNAME}:${TOKEN}" \
            | jq '.plugins[]|{shortName}' -c \
            | grep ${PLUGIN_NAME} >/dev/null; echo $?) \
            -ne "0" ]]; do
    echo -n .
    sleep 2
  done
}

function waitForPluginManager() {
  while [[ $(curl -s -k "${JENKINS_URL}/pluginManager/api/json?depth=1" \
            -u "${JENKINS_USERNAME}:${TOKEN}") \
            -ne "200" ]]; do
    echo -n .
    sleep 2
  done
}