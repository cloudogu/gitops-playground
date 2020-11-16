#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
set -x

SCM_USER=scmadmin
SCM_PWD=scmadmin
HOST=localhost:8080

function main() {

  cd /tmp && wget https://github.com/dtschan/curl-static/releases/download/v7.63.0/curl && chmod +x curl
  while [[ "$(./curl -s -L -o /dev/null -w ''%{http_code}'' "http://${HOST}/scm")" -ne "200" ]]; do sleep 5; done;

  setConfig

  addUser "${JENKINS_USERNAME}" "${JENKINS_PASSWORD}" "jenkins@mail.de"
  addUser "${FLUX_USERNAME}" "${FLUX_PASSWORD}" "flux@mail.de"

  addRepo "cluster" "gitops"
  setPermission "cluster" "gitops" "${JENKINS_USERNAME}" "WRITE"
  setPermission "cluster" "gitops" "${FLUX_USERNAME}" "READ"

  addRepo "application" "petclinic-plain"
  setPermission "application" "petclinic-plain" "${JENKINS_USERNAME}" "WRITE"

  addRepo "argocd" "nginx-helm"
  setPermission "argocd" "nginx-helm" "${ARGOCD_USERNAME}" "WRITE"
  addRepo "argocd" "petclinic-plain"
  setPermission "argocd" "petclinic-plain" "${JENKINS_USERNAME}" "WRITE"
  addRepo "argocd" "gitops"
  setPermission "argocd" "gitops" "${JENKINS_USERNAME}" "WRITE"
  setPermission "argocd" "gitops" "${ARGOCD_USERNAME}" "READ"
  
  addRepo "application" "spring-boot-helm-chart"
  setPermission "application" "spring-boot-helm-chart" "${FLUX_USERNAME}" "READ"

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
    --data "{\"proxyPassword\":null,\"proxyPort\":8080,\"proxyServer\":\"proxy.mydomain.com\",\"proxyUser\":null,\"enableProxy\":false,\"realmDescription\":\"SONIA :: SCM Manager\",\"disableGroupingGrid\":false,\"dateFormat\":\"YYYY-MM-DD HH:mm:ss\",\"anonymousAccessEnabled\":false,\"anonymousMode\":\"OFF\",\"baseUrl\":\"http://scmm-scm-manager:9091/scm\",\"forceBaseUrl\":false,\"loginAttemptLimit\":-1,\"proxyExcludes\":[],\"skipFailedAuthenticators\":false,\"pluginUrl\":\"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}\",\"loginAttemptLimitTimeout\":300,\"enabledXsrfProtection\":true,\"namespaceStrategy\":\"CustomNamespaceStrategy\",\"loginInfoUrl\":\"https://login-info.scm-manager.org/api/v1/login-info\",\"releaseFeedUrl\":\"https://scm-manager.org/download/rss.xml\",\"mailDomainName\":\"scm-manager.local\",\"_links\":{\"self\":{\"href\":\"http://localhost:9091/scm/api/v2/config\"},\"update\":{\"href\":\"http://localhost:9091/scm/api/v2/config\"}},\"adminGroups\":[],\"adminUsers\":[]}" \
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