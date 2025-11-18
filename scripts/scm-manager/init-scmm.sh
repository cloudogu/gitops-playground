#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
set -x

ABSOLUTE_BASEDIR="$(cd "$(dirname $0)" && pwd)"
source ${ABSOLUTE_BASEDIR}/../utils.sh

if [[ $TRACE == true ]]; then
  set -x
fi

SCMM_PROTOCOL=http

PLAYGROUND_DIR="$(cd ${ABSOLUTE_BASEDIR} && cd ../.. && pwd)"

if [[ $INSECURE == true ]]; then
  CURL_HOME="${PLAYGROUND_DIR}"
  export CURL_HOME
  export GIT_SSL_NO_VERIFY=1
fi

function initSCMM() {
  
  SCMM_HOST=$(getHost "${SCMM_URL}")
  SCMM_PROTOCOL=$(getProtocol "${SCMM_URL}")

  echo "SCM provider: ${SCM_PROVIDER}"
  if [[ ${INTERNAL_SCMM} == true ]]; then
    setExternalHostnameIfNecessary 'SCMM' 'scmm' "${NAME_PREFIX}scm-manager"
  fi
  
  [[ "${SCMM_URL}" != *scm ]] && SCMM_URL=${SCMM_URL}/scm

  if [[ ${SCM_PROVIDER} == "scm-manager" ]]; then
      configureScmmManager
  fi
}

function pushHelmChartRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)
  git clone -n "${SPRING_BOOT_HELM_CHART_REPO}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${SPRING_BOOT_HELM_CHART_COMMIT} --quiet

    # Create a defined version to use in demo applications
    git tag 1.0.0

    git branch --quiet -d main
    git checkout --quiet -b main

    waitForScmManager

    local remote_url

    if [[ ${SCM_PROVIDER} == "scm-manager" ]]; then
        remote_url="${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/${SCM_ROOT_PATH}/${TARGET_REPO_SCMM}"
    elif [[ ${SCM_PROVIDER} == "gitlab" ]]; then
        remote_url="${SCMM_PROTOCOL}://oauth2:${SCMM_PASSWORD}@${SCMM_HOST}/${SCM_ROOT_PATH}/${TARGET_REPO_SCMM}.git"
    else
        echo "Unsupported SCM provider: ${SCM_PROVIDER}"
        return 1
    fi

    git push "${remote_url}" HEAD:main --force --quiet
    git push "${remote_url}" refs/tags/1.0.0 --quiet --force
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function pushHelmChartRepoWithDependency() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)
  git clone -n "${SPRING_BOOT_HELM_CHART_REPO}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${SPRING_BOOT_HELM_CHART_COMMIT} --quiet

    # Create a defined version to use in demo applications
    git tag 1.0.0

    git branch --quiet -d main
    git checkout --quiet -b main

    echo "dependencies:
- name: podinfo
  version: \"5.2.0\"
  repository: \"https://stefanprodan.github.io/podinfo\"" >>./Chart.yaml

    git commit -a -m "Added dependency" --quiet

    waitForScmManager

    local remote_url

    if [[ ${SCM_PROVIDER} == "scm-manager" ]]; then
        remote_url="${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/${SCM_ROOT_PATH}/${TARGET_REPO_SCMM}"
    elif [[ ${SCM_PROVIDER} == "gitlab" ]]; then
        remote_url="${SCMM_PROTOCOL}://oauth2:${SCMM_PASSWORD}@${SCMM_HOST}/${SCM_ROOT_PATH}/${TARGET_REPO_SCMM}.git"
    else
        echo "Unsupported SCM provider: ${SCM_PROVIDER}"
        return 1
    fi

    git push "${remote_url}" HEAD:main --force --quiet
    git push "${remote_url}" refs/tags/1.0.0 --quiet --force
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function pushRepoMirror() {
  SOURCE_REPO_URL="$1"
  TARGET_REPO_SCMM="$2"
  DEFAULT_BRANCH="${3:-main}"

  TMP_REPO=$(mktemp -d)
  git clone --bare "${SOURCE_REPO_URL}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    waitForScmManager

    local remote_url

    if [[ ${SCM_PROVIDER} == "scm-manager" ]]; then
        remote_url="${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/${SCM_ROOT_PATH}/${TARGET_REPO_SCMM}"
    elif [[ ${SCM_PROVIDER} == "gitlab" ]]; then
        remote_url="${SCMM_PROTOCOL}://oauth2:${SCMM_PASSWORD}@${SCMM_HOST}/${SCM_ROOT_PATH}/${TARGET_REPO_SCMM}.git"
    else
        echo "Unsupported SCM provider: ${SCM_PROVIDER}"
        return 1
    fi
    git push --mirror "${remote_url}" --force --quiet
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}" "${DEFAULT_BRANCH}"
}

function setDefaultBranch() {
  TARGET_REPO_SCMM="$1"
  DEFAULT_BRANCH="${2:-main}"

  curl -s -L -X PUT -H 'Content-Type: application/vnd.scmm-gitConfig+json' \
    --data-raw "{\"defaultBranch\":\"${DEFAULT_BRANCH}\"}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/config/git/${TARGET_REPO_SCMM}"
}

function configureScmmManager() {
  GITOPS_PASSWORD=${SCMM_PASSWORD}

  METRICS_USERNAME="${NAME_PREFIX}metrics"
  METRICS_PASSWORD=${SCMM_PASSWORD}

  waitForScmManager

  setConfig

  addUser "${GITOPS_USERNAME}" "${GITOPS_PASSWORD}" "changeme@test.local"
  addUser "${METRICS_USERNAME}" "${METRICS_PASSWORD}" "changeme@test.local"
  setPermissionForUser "${METRICS_USERNAME}" "metrics:read"

  # Install necessary plugins
  installScmmPlugins

  configJenkins
}

function installScmmPlugins() {
  if [[ "${SKIP_PLUGINS:-false}" == "true" ]]; then
    echo "Skipping SCM plugin installation due to SKIP_PLUGINS=true"
    return
  fi

  if [ -n "${JENKINS_URL_FOR_SCMM}" ]; then
    installScmmPlugin "scm-jenkins-plugin" "false"
  fi

  local restart_flag="true"
  [[ "${SKIP_RESTART}" == "true" ]] && {
    echo "Skipping SCMM restart due to SKIP_RESTART=true"
    restart_flag="false"
  }

  installScmmPlugin "scm-mail-plugin" "false"
  installScmmPlugin "scm-review-plugin" "false"
  installScmmPlugin "scm-code-editor-plugin" "false"
  installScmmPlugin "scm-editor-plugin" "false"
  installScmmPlugin "scm-landingpage-plugin" "false"
  installScmmPlugin "scm-el-plugin" "false"
  installScmmPlugin "scm-readme-plugin" "false"
  installScmmPlugin "scm-webhook-plugin" "false"
  installScmmPlugin "scm-ci-plugin" "false"
  # Last plugin usually triggers restart
  installScmmPlugin "scm-metrics-prometheus-plugin" "$restart_flag"
  # Wait for SCM-Manager to restart
  if [[ "$restart_flag" == "true" ]]; then
    sleep 1
    waitForScmManager
  fi
}

function addRepo() {
  NAMESPACE="${1}"
  NAME="${2}"
  DESCRIPTION="${3:-}"
  local PARAM="${4:-false}"
  if [[ "${PARAM,,}" == "true" ]]; then
    HOST=$(getHost "${CENTRAL_SCM_URL%/}")  # Remove trailing slash if present, we already got this in the api requests: /api
    USERNAME="${CENTRAL_SCM_USERNAME}"
    PASSWORD="${CENTRAL_SCM_PASSWORD}"
  else
    HOST="${SCMM_HOST}"
    USERNAME="${SCMM_USERNAME}"
    PASSWORD="${SCMM_PASSWORD}"
  fi

  printf 'Adding Repo %s/%s ... ' "${NAMESPACE}" "${NAME}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST \
    -H "Content-Type: application/vnd.scmm-repository+json;v=2" \
    --data "{\"name\":\"${NAME}\",\"namespace\":\"${NAMESPACE}\",\"type\":\"git\",\"description\":\"${DESCRIPTION}\",\"contextEntries\":{},\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${USERNAME}:${PASSWORD}@${HOST}/api/v2/repositories/?initialize=true") && EXIT_STATUS=$? || EXIT_STATUS=$?

  if [ $EXIT_STATUS -ne 0 ]; then
    echo "Adding Repo failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function addUser() {
  printf 'Adding User %s ... ' "${1}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-user+json;v=2" \
    --data "{\"name\":\"${1}\",\"displayName\":\"${1}\",\"mail\":\"${3}\",\"external\":false,\"password\":\"${2}\",\"active\":true,\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/users") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Adding User failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setPermission() {
  printf 'Setting permission on Repo %s/%s for %s... ' "${1}" "${2}" "${3}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-repositoryPermission+json" \
    --data "{\"name\":\"${3}\",\"role\":\"${4}\",\"verbs\":[],\"groupPermission\":false}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/repositories/${1}/${2}/permissions/") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Setting Permission failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setPermissionForNamespace() {
  printf 'Setting permission %s on Namespace %s for %s... ' "${3}" "${1}" "${2}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-repositoryPermission+json;v=2" \
    --data "{\"name\":\"${2}\",\"role\":\"${3}\",\"verbs\":[],\"groupPermission\":false}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/namespaces/${1}/permissions/") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Setting Permission failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setPermissionForUser() {
  printf 'Setting permission %s for %s... ' "${2}" "${1}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H "Content-Type: application/vnd.scmm-permissionCollection+json;v=2" \
    --data "{\"permissions\":[\"${2}\"]}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/users/${1}/permissions") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Setting Permission failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function installScmmPlugin() {
  DO_RESTART="?restart=false"
  if [[ "${2}" == true ]]; then
    DO_RESTART="?restart=true"
  fi

  printf 'Installing Plugin %s ... ' "${1}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "accept: */*" --data "" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/plugins/available/${1}/install${DO_RESTART}") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Installing Plugin failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function configJenkins() {

  if [ -n "${JENKINS_URL_FOR_SCMM}" ]; then
    printf 'Configuring Jenkins plugin in SCM-Manager ... '

    STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H 'Content-Type: application/json' \
      --data-raw "{\"disableRepositoryConfiguration\":false,\"disableMercurialTrigger\":false,\"disableGitTrigger\":false,\"disableEventTrigger\":false,\"url\":\"${JENKINS_URL_FOR_SCMM}\"}" \
      "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/config/jenkins/") && EXIT_STATUS=$? || EXIT_STATUS=$?
    if [ $EXIT_STATUS != 0 ]; then
      echo "Configuring Jenkins failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
      exit $EXIT_STATUS
    fi

    printStatus "${STATUS}"
  fi
}

function waitForScmManager() {

  echo -n "Waiting for Scmm to become available at ${SCMM_PROTOCOL}://${SCMM_HOST}/api/v2"

  HTTP_CODE="0"
  while [[ "${HTTP_CODE}" -ne "200" ]]; do
    HTTP_CODE="$(curl -s -L -o /dev/null --max-time 10 -w ''%{http_code}'' "${SCMM_PROTOCOL}://${SCMM_HOST}/api/v2")" || true
    echo -n "."
    sleep 2
  done
  echo ""
}

function getHost() {
  local SCMM_URL="$1"

  local CLEANED_URL="${SCMM_URL#http://}"
  CLEANED_URL="${CLEANED_URL#https://}"

  echo "${CLEANED_URL}"
}

function getProtocol() {
  local SCMM_URL="$1"
  if [[ "${SCMM_URL}" == https://* ]]; then
    echo "https"
  elif [[ "${SCMM_URL}" == http://* ]]; then
    echo "http"
  fi
}

function printStatus() {
  STATUS_CODE=${1}
  if [ "${STATUS_CODE}" -eq 200 ] || [ "${STATUS_CODE}" -eq 201 ] || [ "${STATUS_CODE}" -eq 302 ] || [ "${STATUS_CODE}" -eq 204 ] || [ "${STATUS_CODE}" -eq 409 ]; then
    echo -e ' \u2705'
  else
    echo -e ' \u274c ' "(status code: $STATUS_CODE)"
  fi
}

initSCMM "$@"