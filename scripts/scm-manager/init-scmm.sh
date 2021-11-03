#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

SCMM_USER=scmadmin
SCMM_PWD=scmadmin
SCMM_PROTOCOL=http
# Note that starting with 2.21.0 the default admin is no longer present, which will require some changes in this script 
# https://scm-manager.org/docs/2.21.x/en/first-startup/
SCMM_HELM_CHART_VERSION=2.20.0

function deployLocalScmmManager() {
  REMOTE_CLUSTER=${1}

  helm upgrade -i scmm --values scm-manager/values.yaml \
    $(scmmHelmSettingsForRemoteCluster) \
    --version ${SCMM_HELM_CHART_VERSION} scm-manager/scm-manager -n default
}

function configureScmmManager() {
  ADMIN_USERNAME=${1}
  ADMIN_PASSWORD=${2}
  SCMM_HOST=$(getHost ${3})
  SCMM_PROTOCOL=$(getProtocol ${3})
  SCMM_JENKINS_URL=${4}
  # When running in k3d, BASE_URL must be the internal URL. Otherwise webhooks from SCMM->Jenkins will fail, as
  # They contain Repository URLs create with BASE_URL. Jenkins uses the internal URL for repos. So match is only
  # successful, when SCM also sends the Repo URLs using the internal URL
  BASE_URL=${5}
  IS_LOCAL=${6}
  INSTALL_FLUXV1="${7}"
  INSTALL_FLUXV2="${8}"
  INSTALL_ARGOCD="${9}"

  GITOPS_USERNAME="gitops"
  GITOPS_PASSWORD=${ADMIN_PASSWORD}

  waitForScmManager

  # We can not set the initial user through SCM-Manager configuration (as of SCMM 2.12.0), so we set the user via REST API
  if [[ $IS_LOCAL == true ]]; then
    # TODO this is not idempotent - on the second call the admin is aleady gone and this yield 401
    # Unfortunately we just ignore all HTTP errors. Just calling printStatus() whose result is only printed if --debug is used.
    addUser "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}" "admin@mail.de"
    setAdmin "${ADMIN_USERNAME}"
    deleteUser "${SCMM_USER}" "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}"
  fi

  SCMM_USER=${ADMIN_USERNAME}
  SCMM_PWD=${ADMIN_PASSWORD}

  setConfig

  addUser "${GITOPS_USERNAME}" "${GITOPS_PASSWORD}" "gitops@mail.de"

  ### FluxV1 Repos
  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_FLUXV1 == true ]]; then
    addRepo "fluxv1" "gitops"
    setPermission "fluxv1" "gitops" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "fluxv1" "petclinic-plain"
    setPermission "fluxv1" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
    addRepo "fluxv1" "petclinic-helm"
    setPermission "fluxv1" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "fluxv1" "nginx-helm"
    setPermission "fluxv1" "nginx-helm" "${GITOPS_USERNAME}" "WRITE"
  fi
  
  ### FluxV2 Repos
  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_FLUXV2 == true ]]; then
    addRepo "fluxv2" "gitops"
    setPermission "fluxv2" "gitops" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "fluxv2" "petclinic-plain"
    setPermission "fluxv2" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
  fi

  ### ArgoCD Repos
  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_ARGOCD == true ]]; then
    addRepo "argocd" "nginx-helm"
    setPermission "argocd" "nginx-helm" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "argocd" "petclinic-plain"
    setPermission "argocd" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "argocd" "petclinic-helm"
    setPermission "argocd" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "argocd" "control-app"
    setPermission "argocd" "control-app" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "argocd" "gitops"
    setPermission "argocd" "gitops" "${GITOPS_USERNAME}" "WRITE"
  fi

  ### Common Repos
  addRepo "common" "spring-boot-helm-chart"
  setPermission "common" "spring-boot-helm-chart" "${GITOPS_USERNAME}" "WRITE"

  addRepo "common" "spring-boot-helm-chart-with-dependency"
  setPermission "common" "spring-boot-helm-chart-with-dependency" "${GITOPS_USERNAME}" "WRITE"

  addRepo "common" "gitops-build-lib"
  setPermission "common" "gitops-build-lib" "${GITOPS_USERNAME}" "WRITE"

  addRepo "common" "ces-build-lib"
  setPermission "common" "ces-build-lib" "${GITOPS_USERNAME}" "WRITE"

  addRepo "exercises" "petclinic-helm"
  setPermission "exercises" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"

  addRepo "exercises" "nginx-validation"
  setPermission "exercises" "nginx-validation" "${GITOPS_USERNAME}" "WRITE"

  addRepo "exercises" "broken-application"
  setPermission "exercises" "broken-application" "${GITOPS_USERNAME}" "WRITE"

  # Install necessary plugins
  installScmmPlugin "scm-mail-plugin" "false"
  installScmmPlugin "scm-review-plugin" "false"
  installScmmPlugin "scm-code-editor-plugin" "false"
  installScmmPlugin "scm-editor-plugin" "false"
  installScmmPlugin "scm-activity-plugin" "false"
  installScmmPlugin "scm-el-plugin" "false"
  installScmmPlugin "scm-jenkins-plugin" "false"
  installScmmPlugin "scm-readme-plugin" "false"
  installScmmPlugin "scm-webhook-plugin" "false"
  installScmmPlugin "scm-ci-plugin" "true"

  # We have to wait 1 second to ensure that the restart is really initiated
  sleep 1
  waitForScmManager

  configJenkins "${SCMM_JENKINS_URL}"
}

function addRepo() {
  printf 'Adding Repo %s/%s ... ' "${1}" "${2}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-repository+json;v=2" \
    --data "{\"name\":\"${2}\",\"namespace\":\"${1}\",\"type\":\"git\",\"contact\":\"admin@mail.de\",\"description\":\"description\",\"contextEntries\":{},\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/repositories/") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Adding Repo failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setConfig() {
  printf 'Setting config'

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H "Content-Type: application/vnd.scmm-config+json;v=2" \
    --data "{\"proxyPassword\":null,\"proxyPort\":8080,\"proxyServer\":\"proxy.mydomain.com\",\"proxyUser\":null,\"enableProxy\":false,\"realmDescription\":\"SONIA :: SCM Manager\",\"disableGroupingGrid\":false,\"dateFormat\":\"YYYY-MM-DD HH:mm:ss\",\"anonymousAccessEnabled\":false,\"anonymousMode\":\"OFF\",\"baseUrl\":\"${BASE_URL}\",\"forceBaseUrl\":false,\"loginAttemptLimit\":-1,\"proxyExcludes\":[],\"skipFailedAuthenticators\":false,\"pluginUrl\":\"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}\",\"loginAttemptLimitTimeout\":300,\"enabledXsrfProtection\":true,\"namespaceStrategy\":\"CustomNamespaceStrategy\",\"loginInfoUrl\":\"https://login-info.scm-manager.org/api/v1/login-info\",\"releaseFeedUrl\":\"https://scm-manager.org/download/rss.xml\",\"mailDomainName\":\"scm-manager.local\",\"adminGroups\":[],\"adminUsers\":[]}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/config") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Setting config failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function addUser() {
  printf 'Adding User %s ... ' "${1}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-user+json;v=2" \
    --data "{\"name\":\"${1}\",\"displayName\":\"${1}\",\"mail\":\"${3}\",\"external\":false,\"password\":\"${2}\",\"active\":true,\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/users") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Adding User failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setAdmin() {
  printf 'Setting Admin %s ... ' "${1}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H "Content-Type: application/vnd.scmm-permissionCollection+json;v=2" \
    --data "{\"permissions\":[\"*\"]}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/users/${1}/permissions") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Setting Admin failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function deleteUser() {
  userToDelete="$1"
  loginUser="$2"
  loginPassword="$3"
  printf 'Deleting User %s ... ' "${userToDelete}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X DELETE \
    "${SCMM_PROTOCOL}://${loginUser}:${loginPassword}@${SCMM_HOST}/api/v2/users/${userToDelete}") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Deleting User failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setPermission() {
  printf 'Setting permission on Repo %s/%s for %s... ' "${1}" "${2}" "${3}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-repositoryPermission+json" \
    --data "{\"name\":\"${3}\",\"role\":\"${4}\",\"verbs\":[],\"groupPermission\":false}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/repositories/${1}/${2}/permissions/") && EXIT_STATUS=$? || EXIT_STATUS=$?
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
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/plugins/available/${1}/install${DO_RESTART}") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Installing Plugin failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function configJenkins() {
  printf 'Configuring Jenkins plugin in SCM-Manager ... '

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H 'Content-Type: application/json' \
    --data-raw "{\"disableRepositoryConfiguration\":false,\"disableMercurialTrigger\":false,\"disableGitTrigger\":false,\"disableEventTrigger\":false,\"url\":\"${1}\"}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/config/jenkins/") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Configuring Jenkins failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function scmmHelmSettingsForRemoteCluster() {
  if [[ $REMOTE_CLUSTER == true ]]; then
    # Default clusters don't allow for node ports < 30.000, so just unset nodePort.
    # A defined nodePort is not needed for remote cluster, where the externalIp is used for accessing SCMM
    echo "--set service.nodePort="
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
  if [[ "${SCMM_URL}" == https://* ]]; then
    echo "${SCMM_URL}" | cut -c 9-
  elif [[ "${SCMM_URL}" == http://* ]]; then
    echo "${SCMM_URL}" | cut -c 8-
  fi
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
