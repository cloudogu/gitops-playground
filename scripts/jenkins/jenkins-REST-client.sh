#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

function authenticate() {
  # get jenkins crumb
  crumb=$(curl -s --cookie-jar /tmp/cookies -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" "http://${JENKINS_URL}/crumbIssuer/api/json" | jq -r '.crumb')

  # get jenkins api token
  token=$(curl -s -X POST -H "Jenkins-Crumb:${crumb}" --cookie /tmp/cookies "http://${JENKINS_URL}/me/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken\?newTokenName\=\foo" -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" | jq -r '.data.tokenValue')
  echo "${token}"
}

function createJob() {
  JOB_NAME=${1}
  JOB_CONFIG=${2}
  printf "Creating job '${JOB_NAME}' ... "
  status=$(curl -s -X POST "http://${JENKINS_URL}/createItem?name=${JOB_NAME}" -u "${JENKINS_USERNAME}:${token}" -H "Content-Type:text/xml" --data "${JOB_CONFIG}" --write-out '%{http_code}')
  printStatus $status
}

function prepareScmManagerNamspaceJob() {
  job_config=$(SCMM_NAMESPACE_JOB_SERVER_URL="${1}" \
               SCMM_NAMESPACE_JOB_NAMESPACE="${2}" \
               SCMM_NAMESPACE_JOB_CREDENTIALS_ID="${3}" \
               envsubst < scripts/jenkins/namespaceJobTemplate.xml)
  echo "${job_config}"
}

function createCredentials() {
  CREDENTIALS_ID=${1}
  USERNAME=${2}
  PASSWORD=${3}
  DESCRIPTION=${4}

  echo "$JENKINS_USERNAME" "$JENKINS_URL" "$token"

  printf "Creating credentials for ${CREDENTIALS_ID} ... "
  status=$(curl -s -X POST "http://${JENKINS_URL}/credentials/store/system/domain/_/createCredentials" -u "${JENKINS_USERNAME}:${token}" --data-urlencode 'json={
    "credentials": {
      "scope": "GLOBAL",
      "id": "'${CREDENTIALS_ID}'",
      "username": "'${USERNAME}'",
      "password": "'${PASSWORD}'",
      "description": "'${DESCRIPTION}'",
      "$class": "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl"}}' --write-out '%{http_code}')

  printStatus "${status}"
}

function installPlugin() {
  printf "Installing plugin $1 v$2 ... "
  status=$(curl -s -X POST "http://${JENKINS_URL}/pluginManager/installNecessaryPlugins" -u "${JENKINS_USERNAME}:${token}" -d '<jenkins><install plugin="'$1'@'$2'"/></jenkins>' -H 'Content-Type: text/xml' --write-out '%{http_code}')
  printStatus "${status}"
}

function safeRestart() {
  curl -X POST "http://${JENKINS_URL}/safeRestart" -u "${JENKINS_USERNAME}:${token}"
}

function printStatus() {
  if [ $1 -eq 200 ] || [ $1 -eq 201 ] || [ $1 -eq 202 ] || [ $1 -eq 302 ]
  then
    echo -e '\u2705'
  else
    echo -e '\u274c'
  fi
}