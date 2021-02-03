#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
set -x

SCM_USER=scmadmin
SCM_PWD=scmadmin
HOST=localhost:8080

function main() {

  # We need to download curl to use the REST API, because the installed wget lacks functionality
  cd /tmp && wget https://github.com/dtschan/curl-static/releases/download/v7.63.0/curl && chmod +x curl
  # Wait for the SCM-Manager to be up and running
  while [[ "$(./curl -s -L -o /dev/null -w ''%{http_code}'' "http://${HOST}/scm")" -ne "200" ]]; do sleep 5; done;

  setConfig

  addUser "${GITOPS_USERNAME}" "${GITOPS_PASSWORD}" "gitops@mail.de"

  # We can not set the initial user through SCM-Manager configuration (as of SCMM 2.12.0), so we set the user via REST API
  addUser "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}" "admin@mail.de"
  setAdmin "${ADMIN_USERNAME}"

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

  configJenkins

  # delete the default initial user scmadmin/scmadmin
  deleteUser "${SCM_USER}" "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}"

  rm curl
}

function addRepo() {
  ./curl -i -L -X POST -H "Content-Type: application/vnd.scmm-repository+json;v=2" \
    --data "{\"name\":\"${2}\",\"namespace\":\"${1}\",\"type\":\"git\",\"contact\":\"admin@mail.de\",\"description\":\"description\",\"contextEntries\":{},\"_links\":{}}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/repositories/"
}

function setConfig() {
  ./curl -i -L -X PUT -H "Content-Type: application/vnd.scmm-config+json;v=2" \
    --data "{\"proxyPassword\":null,\"proxyPort\":8080,\"proxyServer\":\"proxy.mydomain.com\",\"proxyUser\":null,\"enableProxy\":false,\"realmDescription\":\"SONIA :: SCM Manager\",\"disableGroupingGrid\":false,\"dateFormat\":\"YYYY-MM-DD HH:mm:ss\",\"anonymousAccessEnabled\":false,\"anonymousMode\":\"PROTOCOL_ONLY\",\"baseUrl\":\"http://scmm-scm-manager/scm\",\"forceBaseUrl\":false,\"loginAttemptLimit\":-1,\"proxyExcludes\":[],\"skipFailedAuthenticators\":false,\"pluginUrl\":\"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}\",\"loginAttemptLimitTimeout\":300,\"enabledXsrfProtection\":true,\"namespaceStrategy\":\"CustomNamespaceStrategy\",\"loginInfoUrl\":\"https://login-info.scm-manager.org/api/v1/login-info\",\"releaseFeedUrl\":\"https://scm-manager.org/download/rss.xml\",\"mailDomainName\":\"scm-manager.local\",\"adminGroups\":[],\"adminUsers\":[]}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/config"
}

function addUser() {
  ./curl -i -L -X POST -H "Content-Type: application/vnd.scmm-user+json;v=2" \
    --data "{\"name\":\"${1}\",\"displayName\":\"${1}\",\"mail\":\"${3}\",\"password\":\"${2}\",\"active\":true,\"_links\":{}}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/users"
}

function setAdmin() {
  ./curl -i -L -X PUT -H "Content-Type: application/vnd.scmm-permissionCollection+json;v=2" \
    --data "{\"permissions\":[\"*\"]}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/users/${1}/permissions"
}

function deleteUser() {
  userToDelete="$1"
  loginUser="$2"
  loginPassword="$3"
  ./curl -i -L -X DELETE \
    "http://${loginUser}:${loginPassword}@${HOST}/scm/api/v2/users/${userToDelete}"
}

function setPermission() {
  ./curl -i -L -X POST -H "Content-Type: application/vnd.scmm-repositoryPermission+json" \
    --data "{\"name\":\"${3}\",\"role\":\"${4}\",\"verbs\":[],\"groupPermission\":false}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/repositories/${1}/${2}/permissions/"
}

function configJenkins() {
  ./curl -i -L -X PUT -H 'Content-Type: application/json' \
    --data-raw '{"disableRepositoryConfiguration":false,"disableMercurialTrigger":false,"disableGitTrigger":false,"disableEventTrigger":false,"url":"http://jenkins:8080"}' \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/config/jenkins/"
}

main "$@"; exit