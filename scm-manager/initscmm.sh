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

  configJenkins
  rm curl
}

function addRepo() {
  ./curl -i -L -X POST -H "Content-Type: application/vnd.scmm-repository+json;v=2" \
    --data "{\"name\":\"${2}\",\"namespace\":\"${1}\",\"type\":\"git\",\"contact\":\"admin@mail.de\",\"description\":\"description\",\"contextEntries\":{},\"_links\":{}}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/repositories/"
}

function setConfig() {
  ./curl -i -L -X PUT -H "Content-Type: application/vnd.scmm-config+json;v=2" \
    --data "{\"proxyPassword\":null,\"proxyPort\":8080,\"proxyServer\":\"proxy.mydomain.com\",\"proxyUser\":null,\"enableProxy\":false,\"realmDescription\":\"SONIA :: SCM Manager\",\"disableGroupingGrid\":false,\"dateFormat\":\"YYYY-MM-DD HH:mm:ss\",\"anonymousAccessEnabled\":false,\"anonymousMode\":\"PROTOCOL_ONLY\",\"baseUrl\":\"http://scmm-scm-manager:9091/scm\",\"forceBaseUrl\":false,\"loginAttemptLimit\":-1,\"proxyExcludes\":[],\"skipFailedAuthenticators\":false,\"pluginUrl\":\"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}\",\"loginAttemptLimitTimeout\":300,\"enabledXsrfProtection\":true,\"namespaceStrategy\":\"CustomNamespaceStrategy\",\"loginInfoUrl\":\"https://login-info.scm-manager.org/api/v1/login-info\",\"releaseFeedUrl\":\"https://scm-manager.org/download/rss.xml\",\"mailDomainName\":\"scm-manager.local\",\"_links\":{\"self\":{\"href\":\"http://localhost:9091/scm/api/v2/config\"},\"update\":{\"href\":\"http://localhost:9091/scm/api/v2/config\"}},\"adminGroups\":[],\"adminUsers\":[]}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/config"
}

function addUser() {
  ./curl -i -L -X POST -H "Content-Type: application/vnd.scmm-user+json;v=2" \
    --data "{\"name\":\"${1}\",\"displayName\":\"${1}\",\"mail\":\"${3}\",\"password\":\"${2}\",\"active\":true,\"_links\":{}}" \
    "http://${SCM_USER}:${SCM_PWD}@${HOST}/scm/api/v2/users"
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