#!/usr/bin/env bash
set -o errexit -o pipefail

function curlJenkins() {
  curl -s -H "Jenkins-Crumb:$(crumb)" --cookie /tmp/cookies \
    -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" \
    "$@"
}

function crumb() {
    
  RESPONSE=$(curl -s --cookie-jar /tmp/cookies \
       --retry 3 --retry-delay 1 \
       -u "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" --write-out '%{json}' "${JENKINS_URL}/crumbIssuer/api/json" \
       | jq -rsc '(.[1] | .http_code|tostring), (.[0] | .crumb)') && EXIT_STATUS=$? || EXIT_STATUS=$?
  
  # Convert to array
  mapfile -t RESPONSE <<< ${RESPONSE}
  if [ $EXIT_STATUS != 0 ]
    then
      echo "Creating Credentials failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${RESPONSE[0]}"
      exit $EXIT_STATUS
  fi
  echo ${RESPONSE[1]}
}

function installPlugin() {
  PLUGIN_PATH=${1}
  PLUGIN_FILENAME="${PLUGIN_PATH##*/}"
  PLUGIN_NAME="${PLUGIN_FILENAME%.*}"
  
  printf 'Installing plugin %s from %s ...' "${PLUGIN_NAME}" "${PLUGIN_PATH}"

  STATUS=$(postPlugin "${PLUGIN_PATH}")

  printStatus "${STATUS}"
}

function postPlugin() {
  PLUGIN_PATH=${1}

  STATUS=$(curlJenkins --fail -L -o /dev/null --write-out '%{http_code}' \
          "-F file=@${PLUGIN_PATH}" \
          -F 'a=' \
          "${JENKINS_URL}/pluginManager/uploadPlugin") && EXIT_STATUS=$? || EXIT_STATUS=$?
  # Why -F 'a='? See https://github.com/cloudogu/gitops-playground/issues/86
  
  if [ $EXIT_STATUS != 0 ]
    then
      echo "Installing Plugin failed with exit code: curl: ${EXIT_STATUS}, ${STATUS}"
      exit $EXIT_STATUS
  fi

  echo "${STATUS}"
}

function safeRestart() {
  # Don't use -L here, otherwise follows to root page which is 503 on restart. Then fails.
  curlJenkins --fail -o /dev/null --write-out '%{http_code}' \
    -X POST "${JENKINS_URL}/safeRestart" && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]
    then
      echo "Restarting Jenkins failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
      exit $EXIT_STATUS
  fi
}

function checkPluginStatus() {
  # shellcheck disable=SC2016
  # we don't want to expand these variables in single quotes
  GROOVY_SCRIPT=$(env -i PLUGIN_LIST="${1}" \
                 envsubst '${PLUGIN_LIST}' \
                 < scripts/jenkins/pluginCheck.groovy)

  STATUS=$(curlJenkins --fail -L /dev/null \
           -d "script=${GROOVY_SCRIPT}" --user "${JENKINS_USERNAME}:${JENKINS_PASSWORD}" \
           "${JENKINS_URL}/scriptText")

  echo -e "${STATUS#*: }" | sed 's/^.//;s/.$//'
}

function printStatus() {
  STATUS_CODE=${1}
  if [ "${STATUS_CODE}" -eq 200 ] || [ "${STATUS_CODE}" -eq 302 ]
  then
    echo -e ' \u2705'
  else
    echo -e ' \u274c ' "(status code: $STATUS_CODE)"
  fi
}
