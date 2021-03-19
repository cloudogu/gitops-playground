#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
set -x

SCMM_USER=scmadmin
SCMM_PWD=scmadmin
SCMM_HOST=localhost:8080
SCMM_PROTOCOL=http
REMOTE_CLUSTER=false
SCMM_HELM_CHART_VERSION=2.13.0
SCMM_JENKINS_URL=http://jenkins

function deployLocalScmmManager() {
  REMOTE_CLUSTER=${1}

  helm upgrade -i scmm --values scm-manager/values.yaml \
    $(scmmHelmSettingsForRemoteCluster) \
    --version ${SCMM_HELM_CHART_VERSION} scm-manager/scm-manager -n default
}

function configureScmmManager() {
  ADMIN_USERNAME=${1}
  ADMIN_PASSWORD=${2}
  SCMM_HOST=${3}
  SCMM_JENKINS_URL=${4}
  IS_LOCAL=${5}

  GITOPS_USERNAME="gitops"
  GITOPS_PASSWORD=${ADMIN_PASSWORD}
  SCMM_PROTOCOL=$(getProtocol)
  SCMM_HOST=$(getHost)

  # Wait for the SCM-Manager to be up and running
  while [[ "$(curl -I -s -L -o /dev/null -w ''%{http_code}'' "${SCMM_PROTOCOL}://${SCMM_HOST}/scm")" -ne "200" ]]; do sleep 5; done

  setConfig

  # We can not set the initial user through SCM-Manager configuration (as of SCMM 2.12.0), so we set the user via REST API
  if [[ $IS_LOCAL == true ]]; then
    addUser "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}" "admin@mail.de"
    setAdmin "${ADMIN_USERNAME}"
    deleteUser "${SCMM_USER}" "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}"
  fi

  SCMM_USER=${ADMIN_USERNAME}
  SCMM_PWD=${ADMIN_PASSWORD}

  addUser "${GITOPS_USERNAME}" "${GITOPS_PASSWORD}" "gitops@mail.de"

  ### FluxV1 Repos
  addRepo "fluxv1" "gitops"
  setPermission "fluxv1" "gitops" "${GITOPS_USERNAME}" "WRITE"

  addRepo "fluxv1" "petclinic-plain"
  setPermission "fluxv1" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
  addRepo "fluxv1" "petclinic-helm"
  setPermission "fluxv1" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"

  addRepo "fluxv1" "nginx-helm"
  setPermission "fluxv1" "nginx-helm" "${GITOPS_USERNAME}" "WRITE"

  ### FluxV2 Repos
  addRepo "fluxv2" "gitops"
  setPermission "fluxv2" "gitops" "${GITOPS_USERNAME}" "WRITE"

  addRepo "fluxv2" "petclinic-plain"
  setPermission "fluxv2" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"

  ### ArgoCD Repos
  addRepo "argocd" "nginx-helm"
  setPermission "argocd" "nginx-helm" "${GITOPS_USERNAME}" "WRITE"

  addRepo "argocd" "petclinic-plain"
  setPermission "argocd" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"

  addRepo "argocd" "gitops"
  setPermission "argocd" "gitops" "${GITOPS_USERNAME}" "WRITE"

  ### Common Repos
  addRepo "common" "spring-boot-helm-chart"
  setPermission "common" "spring-boot-helm-chart" "_anonymous" "READ"
  addRepo "common" "gitops-build-lib"
  setPermission "common" "gitops-build-lib" "_anonymous" "READ"
  addRepo "common" "ces-build-lib"
  setPermission "common" "ces-build-lib" "_anonymous" "READ"

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

  configJenkins "${SCMM_JENKINS_URL}"

  export SCMM_HOST
  export SCMM_PROTOCOL
}

function addRepo() {
  curl -i -L -X POST -H "Content-Type: application/vnd.scmm-repository+json;v=2" \
    --data "{\"name\":\"${2}\",\"namespace\":\"${1}\",\"type\":\"git\",\"contact\":\"admin@mail.de\",\"description\":\"description\",\"contextEntries\":{},\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/repositories/"
}

function setConfig() {
  curl -i -L -X PUT -H "Content-Type: application/vnd.scmm-config+json;v=2" \
    --data "{\"proxyPassword\":null,\"proxyPort\":8080,\"proxyServer\":\"proxy.mydomain.com\",\"proxyUser\":null,\"enableProxy\":false,\"realmDescription\":\"SONIA :: SCM Manager\",\"disableGroupingGrid\":false,\"dateFormat\":\"YYYY-MM-DD HH:mm:ss\",\"anonymousAccessEnabled\":false,\"anonymousMode\":\"PROTOCOL_ONLY\",\"baseUrl\":\"http://scmm-scm-manager/scm\",\"forceBaseUrl\":false,\"loginAttemptLimit\":-1,\"proxyExcludes\":[],\"skipFailedAuthenticators\":false,\"pluginUrl\":\"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}\",\"loginAttemptLimitTimeout\":300,\"enabledXsrfProtection\":true,\"namespaceStrategy\":\"CustomNamespaceStrategy\",\"loginInfoUrl\":\"https://login-info.scm-manager.org/api/v1/login-info\",\"releaseFeedUrl\":\"https://scm-manager.org/download/rss.xml\",\"mailDomainName\":\"scm-manager.local\",\"adminGroups\":[],\"adminUsers\":[]}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/config"
}

function addUser() {
  curl -i -L -X POST -H "Content-Type: application/vnd.scmm-user+json;v=2" \
    --data "{\"name\":\"${1}\",\"displayName\":\"${1}\",\"mail\":\"${3}\",\"external\":false,\"password\":\"${2}\",\"active\":true,\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/users"
}

function setAdmin() {
  curl -i -L -X PUT -H "Content-Type: application/vnd.scmm-permissionCollection+json;v=2" \
    --data "{\"permissions\":[\"*\"]}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/users/${1}/permissions"
}

function deleteUser() {
  userToDelete="$1"
  loginUser="$2"
  loginPassword="$3"
  curl -i -L -X DELETE \
    "${SCMM_PROTOCOL}://${loginUser}:${loginPassword}@${SCMM_HOST}/scm/api/v2/users/${userToDelete}"
}

function setPermission() {
  curl -i -L -X POST -H "Content-Type: application/vnd.scmm-repositoryPermission+json" \
    --data "{\"name\":\"${3}\",\"role\":\"${4}\",\"verbs\":[],\"groupPermission\":false}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/repositories/${1}/${2}/permissions/"
}

function installScmmPlugin() {
  if [[ "${2}" == true ]]; then
    curl -i -L -X POST -H "accept: */*" --data "" \
      "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/plugins/available/${1}/install?restart=true"
    while [[ "$(curl -s -L -o /dev/null -w ''%{http_code}'' "${SCMM_PROTOCOL}://${SCMM_HOST}/scm")" -ne "200" ]]; do sleep 5; done
  else
    curl -i -L -X POST -H "accept: */*" --data "" \
      "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/plugins/available/${1}/install"
  fi

}

function configJenkins() {
  curl -i -L -X PUT -H 'Content-Type: application/json' \
    --data-raw "{\"disableRepositoryConfiguration\":false,\"disableMercurialTrigger\":false,\"disableGitTrigger\":false,\"disableEventTrigger\":false,\"url\":\"${1}\"}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/scm/api/v2/config/jenkins/"
}

function scmmHelmSettingsForRemoteCluster() {
  if [[ $REMOTE_CLUSTER == true ]]; then
    # Default clusters don't allow for node ports < 30.000, so just unset nodePort.
    # A defined nodePort is not needed for remote cluster, where the externalIp is used for accessing SCMM
    echo "--set service.nodePort="
  fi
}

function getHost() {
  if [[ $SCMM_HOST == https://* ]]; then
    echo "$SCMM_HOST" | cut -c 9-
  elif [[ $SCMM_HOST == http://* ]]; then
    echo "$SCMM_HOST" | cut -c 8-
  fi
}

function getProtocol() {
  if [[ $SCMM_HOST == https://* ]]; then
    echo "$SCMM_HOST" | cut -c -5
  elif [[ $SCMM_HOST == http://* ]]; then
    echo "$SCMM_HOST" | cut -c -4
  fi
}
