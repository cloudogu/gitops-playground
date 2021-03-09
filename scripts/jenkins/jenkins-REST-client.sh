#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

function authenticate() {
  # get jenkins crumb
  crumb=$(curl -s --cookie-jar /tmp/cookies -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" "${JENKINS_URL}/crumbIssuer/api/json" | jq -r '.crumb')

  # get jenkins api token
  token=$(curl -s -X POST -H "Jenkins-Crumb:${crumb}" --cookie /tmp/cookies "${JENKINS_URL}/me/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken\?newTokenName\=\foo" -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" | jq -r '.data.tokenValue')
  echo "${token}"
}

function createJob() {
  JOB_NAME=${1}
  SCMM_NAMESPACE_JOB_SERVER_URL=${2}
  SCMM_NAMESPACE_JOB_NAMESPACE=${3}
  SCMM_NAMESPACE_JOB_CREDENTIALS_ID=${4}

  JOB_CONFIG=$(prepareScmManagerNamspaceJob ${SCMM_NAMESPACE_JOB_SERVER_URL} ${SCMM_NAMESPACE_JOB_NAMESPACE} ${SCMM_NAMESPACE_JOB_CREDENTIALS_ID})

  printf "Creating job '${JOB_NAME}' ... "
  status=$(curl -s -o /dev/null -X POST "${JENKINS_URL}/createItem?name=${JOB_NAME}" -u "${JENKINS_USERNAME}:${token}" -H "Content-Type:text/xml" --data "${JOB_CONFIG}" --write-out '%{http_code}')
  printStatus $status
}

function prepareScmManagerNamspaceJob() {
  job_config=$(env -i SCMM_NAMESPACE_JOB_SERVER_URL="${1}" \
               SCMM_NAMESPACE_JOB_NAMESPACE="${2}" \
               SCMM_NAMESPACE_JOB_CREDENTIALS_ID="${3}" \
               envsubst < scripts/jenkins/namespaceJobTemplate.xml)
  echo "${job_config}"
}

function createCredentials() {
  printf "Creating credentials for ${1} ... "
  CRED_CONFIG=$(env -i CREDENTIALS_ID="${1}" \
               USERNAME="${2}" \
               PASSWORD="${3}" \
               DESCRIPTION="${4}" \
               envsubst '${CREDENTIALS_ID},${USERNAME},${PASSWORD},${DESCRIPTION}' < scripts/jenkins/credentialsTemplate.json)

  status=$(curl -s -X POST "${JENKINS_URL}/credentials/store/system/domain/_/createCredentials" -u "${JENKINS_USERNAME}:${token}" --data-urlencode "json=${CRED_CONFIG}" --write-out '%{http_code}')

  printStatus "${status}"
}




function installPlugin() {
  PLUGIN_NAME=${1}
  PLUGIN_VERSION=${2}

  printf "Installing plugin ${PLUGIN_NAME} v${PLUGIN_VERSION} ..."
  status=$(curl -s -o /dev/null -X POST "${JENKINS_URL}/pluginManager/installNecessaryPlugins" -u "${JENKINS_USERNAME}:${token}" -d '<jenkins><install plugin="'${PLUGIN_NAME}'@'${PLUGIN_VERSION}'"/></jenkins>' -H 'Content-Type: text/xml' --write-out '%{http_code}')

  if [ ${status} -eq 200 ] || [ ${status} -eq 302 ]
  then
    waitForPluginInstallation "${PLUGIN_NAME}"
  fi
  printStatus "${status}"
}

function safeRestart() {
  curl -X POST "${JENKINS_URL}/safeRestart" -u "${JENKINS_USERNAME}:${token}"
}

function printStatus() {
  if [ $1 -eq 200 ] || [ $1 -eq 201 ] || [ $1 -eq 202 ] || [ $1 -eq 302 ]
  then
    echo -e ' \u2705'
  else
    echo -e ' \u274c'
  fi
}

function waitForPluginInstallation() {
  PLUGIN_NAME=${1}
  while [[ $(curl -s -k "${JENKINS_URL}/pluginManager/api/json?depth=1" -u "${JENKINS_USERNAME}:${token}" | jq '.plugins[]|{shortName}' -c | grep ${PLUGIN_NAME} >/dev/null; echo $?) -ne "0" ]]; do
    echo -n .
    sleep 2
  done
}