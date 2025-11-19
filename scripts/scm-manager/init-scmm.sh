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


  waitForScmManager

  setConfig


  # Install necessary plugins
  installScmmPlugins

  configJenkins
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


initSCMM "$@"