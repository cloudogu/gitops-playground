#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

SCMM_PROTOCOL=http
SCMM_HELM_CHART_VERSION=2.40.0

function deployLocalScmmManager() {
  local REMOTE_CLUSTER=${1}
  local SET_USERNAME=${2}
  local SET_PASSWORD=${3}

  helm upgrade -i scmm --values scm-manager/values.yaml \
    $(scmmHelmSettingsForRemoteCluster) \
    --version ${SCMM_HELM_CHART_VERSION} scm-manager/scm-manager -n default \
    --set extraArgs="{-Dscm.initialPassword=${SET_PASSWORD},-Dscm.initialUser=${SET_USERNAME}}"
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
  INSTALL_FLUXV2="${7}"
  INSTALL_ARGOCD="${8}"

  GITOPS_USERNAME="gitops"
  GITOPS_PASSWORD=${ADMIN_PASSWORD}

  waitForScmManager

  SCMM_USER=${ADMIN_USERNAME}
  SCMM_PWD=${ADMIN_PASSWORD}

  setConfig

  addUser "${GITOPS_USERNAME}" "${GITOPS_PASSWORD}" "gitops@mail.de"

  ### FluxV2 Repos
  if [[ $INSTALL_FLUXV2 == true ]]; then
    addRepo "fluxv2" "gitops"
    setPermission "fluxv2" "gitops" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "fluxv2" "petclinic-plain"
    setPermission "fluxv2" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
  fi

  ### ArgoCD Repos
  if [[ $INSTALL_ARGOCD == true ]]; then
    setPermissionForNamespace "argocd" "${GITOPS_USERNAME}" "CI-SERVER"

    addRepo "argocd" "nginx-helm-jenkins" "3rd Party app (NGINX) with helm, templated in Jenkins (gitops-build-lib)"
    setPermission "argocd" "nginx-helm-jenkins" "${GITOPS_USERNAME}" "WRITE"
    
    addRepo "argocd" "petclinic-plain" "Java app with plain k8s resources"
    setPermission "argocd" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "argocd" "petclinic-helm" "Java app with custom helm chart"
    setPermission "argocd" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "argocd" "argocd" "GitOps repo for administration of ArgoCD"
    setPermission "argocd" "argocd" "${GITOPS_USERNAME}" "WRITE"
      
    addRepo "argocd" "cluster-resources" "GitOps repo for basic cluster-resources"
    setPermission "argocd" "cluster-resources" "${GITOPS_USERNAME}" "WRITE"
    
    addRepo "argocd" "example-apps" "GitOps repo for examples of end-user applications"
    setPermission "argocd" "example-apps" "${GITOPS_USERNAME}" "WRITE"
  fi

  ### Common Repos
  addRepo "common" "spring-boot-helm-chart"
  setPermission "common" "spring-boot-helm-chart" "${GITOPS_USERNAME}" "WRITE"

  addRepo "common" "spring-boot-helm-chart-with-dependency"
  setPermission "common" "spring-boot-helm-chart-with-dependency" "${GITOPS_USERNAME}" "WRITE"

  addRepo "common" "gitops-build-lib" "Jenkins pipeline shared library for automating deployments via GitOps "
  setPermission "common" "gitops-build-lib" "${GITOPS_USERNAME}" "WRITE"

  addRepo "common" "ces-build-lib" "Jenkins pipeline shared library adding features for Maven, Gradle, Docker, SonarQube, Git and others"
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
  NAMESPACE="${1}"
  NAME="${2}"
  DESCRIPTION="${3:-}"
  
  printf 'Adding Repo %s/%s ... ' "${1}" "${2}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-repository+json;v=2" \
    --data "{\"name\":\"${NAME}\",\"namespace\":\"${NAMESPACE}\",\"type\":\"git\",\"contact\":\"admin@mail.de\",\"description\":\"${DESCRIPTION}\",\"contextEntries\":{},\"_links\":{}}" \
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

function setPermissionForNamespace() {
  printf 'Setting permission %s on Namespace %s for %s... ' "${3}" "${1}" "${2}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X POST -H "Content-Type: application/vnd.scmm-repositoryPermission+json;v=2" \
    --data "{\"name\":\"${2}\",\"role\":\"${3}\",\"verbs\":[],\"groupPermission\":false}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/namespaces/${1}/permissions/") && EXIT_STATUS=$? || EXIT_STATUS=$?
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
