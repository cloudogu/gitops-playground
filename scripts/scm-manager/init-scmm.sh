#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

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
  
  if [[ ${INTERNAL_SCMM} == true ]]; then
    deployLocalScmmManager "${REMOTE_CLUSTER}" "${SCMM_USERNAME}" "${SCMM_PASSWORD}" "${BASE_URL}"
  fi

  setExternalHostnameIfNecessary 'SCMM' 'scmm-scm-manager' 'default'
  [[ "${SCMM_URL}" != *scm ]] && SCMM_URL=${SCMM_URL}/scm

  # When running in k3d, SCMM_BASE_URL must be internal. Otherwise webhooks from SCMM->Jenkins will fail, as
  # they contain repository URLs created with SCMM_BASE_URL. Jenkins uses the internal URL for repos. So match is only
  # successful, when SCM also sends the Repo URLs using the internal URL
  configureScmmManager "${SCMM_USERNAME}" "${SCMM_PASSWORD}" "${SCMM_URL}" "${JENKINS_URL_FOR_SCMM}" \
    "${SCMM_URL_FOR_JENKINS}" "${INTERNAL_SCMM}" "${INSTALL_ARGOCD}"

  pushHelmChartRepo "3rd-party-dependencies/spring-boot-helm-chart"
  pushHelmChartRepoWithDependency "3rd-party-dependencies/spring-boot-helm-chart-with-dependency"
  pushRepoMirror "${GITOPS_BUILD_LIB_REPO}" "3rd-party-dependencies/gitops-build-lib"
  pushRepoMirror "${CES_BUILD_LIB_REPO}" "3rd-party-dependencies/ces-build-lib" 'develop'
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
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet --force
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
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet --force
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
    git push --mirror "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" --force --quiet
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

function deployLocalScmmManager() {

  helm repo add scm-manager https://packages.scm-manager.org/repository/helm-v2-releases/
  helm repo update scm-manager
  helm upgrade -i scmm --values scm-manager/values.yaml \
    $(scmmHelmSettingsForRemoteCluster) $(scmmIngress)\
    --version ${SCMM_HELM_CHART_VERSION} scm-manager/scm-manager -n default \
    --set extraArgs="{-Dscm.initialPassword=${SCMM_PASSWORD},-Dscm.initialUser=${SCMM_USERNAME}}"
}

function scmmIngress() {
    if [[ -n "${BASE_URL}" ]]; then
      if [[ $URL_SEPARATOR_HYPHEN == true ]]; then
        local scmmHost="scmm-$(extractHost "${BASE_URL}")"
      else
        local scmmHost="scmm.$(extractHost "${BASE_URL}")"
      fi
    echo "--set ingress.enabled=true --set ingress.path=/ --set ingress.hosts[0]=${scmmHost}"
    fi
}

function configureScmmManager() {
  ADMIN_USERNAME=${1}
  ADMIN_PASSWORD=${2}
  SCMM_HOST=$(getHost ${3})
  SCMM_PROTOCOL=$(getProtocol ${3})
  SCMM_JENKINS_URL=${4}
  # When running in k3d, SCMM_BASE_URL must be the internal URL. Otherwise webhooks from SCMM->Jenkins will fail, as
  # They contain Repository URLs create with SCMM_BASE_URL. Jenkins uses the internal URL for repos. So match is only
  # successful, when SCM also sends the Repo URLs using the internal URL
  SCMM_BASE_URL=${5}
  IS_LOCAL=${6}
  INSTALL_ARGOCD="${7}"

  GITOPS_USERNAME="${NAME_PREFIX}gitops"
  GITOPS_PASSWORD=${ADMIN_PASSWORD}

  METRICS_USERNAME="${NAME_PREFIX}metrics"
  METRICS_PASSWORD=${ADMIN_PASSWORD}

  waitForScmManager

  SCMM_USER=${ADMIN_USERNAME}
  SCMM_PWD=${ADMIN_PASSWORD}

  setConfig

  addUser "${GITOPS_USERNAME}" "${GITOPS_PASSWORD}" "changeme@test.local"
  addUser "${METRICS_USERNAME}" "${METRICS_PASSWORD}" "changeme@test.local"
  setPermissionForUser "${METRICS_USERNAME}" "metrics:read"

  ### ArgoCD Repos
  if [[ $INSTALL_ARGOCD == true ]]; then
    addRepo "${NAME_PREFIX}argocd" "nginx-helm-jenkins" "3rd Party app (NGINX) with helm, templated in Jenkins (gitops-build-lib)"
    setPermission "${NAME_PREFIX}argocd" "nginx-helm-jenkins" "${GITOPS_USERNAME}" "WRITE"
    
    addRepo "${NAME_PREFIX}argocd" "petclinic-plain" "Java app with plain k8s resources"
    setPermission "${NAME_PREFIX}argocd" "petclinic-plain" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "${NAME_PREFIX}argocd" "petclinic-helm" "Java app with custom helm chart"
    setPermission "${NAME_PREFIX}argocd" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"
  
    addRepo "${NAME_PREFIX}argocd" "argocd" "GitOps repo for administration of ArgoCD"
    setPermission "${NAME_PREFIX}argocd" "argocd" "${GITOPS_USERNAME}" "WRITE"
      
    addRepo "${NAME_PREFIX}argocd" "cluster-resources" "GitOps repo for basic cluster-resources"
    setPermission "${NAME_PREFIX}argocd" "cluster-resources" "${GITOPS_USERNAME}" "WRITE"
    
    addRepo "${NAME_PREFIX}argocd" "example-apps" "GitOps repo for examples of end-user applications"
    setPermission "${NAME_PREFIX}argocd" "example-apps" "${GITOPS_USERNAME}" "WRITE"

    setPermissionForNamespace "${NAME_PREFIX}argocd" "${GITOPS_USERNAME}" "CI-SERVER"
  fi

  ### Repos with replicated dependencies
  addRepo "3rd-party-dependencies" "spring-boot-helm-chart"
  setPermission "3rd-party-dependencies" "spring-boot-helm-chart" "${GITOPS_USERNAME}" "WRITE"

  addRepo "3rd-party-dependencies" "spring-boot-helm-chart-with-dependency"
  setPermission "3rd-party-dependencies" "spring-boot-helm-chart-with-dependency" "${GITOPS_USERNAME}" "WRITE"

  addRepo "3rd-party-dependencies" "gitops-build-lib" "Jenkins pipeline shared library for automating deployments via GitOps "
  setPermission "3rd-party-dependencies" "gitops-build-lib" "${GITOPS_USERNAME}" "WRITE"

  addRepo "3rd-party-dependencies" "ces-build-lib" "Jenkins pipeline shared library adding features for Maven, Gradle, Docker, SonarQube, Git and others"
  setPermission "3rd-party-dependencies" "ces-build-lib" "${GITOPS_USERNAME}" "WRITE"

  ### Exercise Repos
  addRepo "${NAME_PREFIX}exercises" "petclinic-helm"
  setPermission "${NAME_PREFIX}exercises" "petclinic-helm" "${GITOPS_USERNAME}" "WRITE"

  addRepo "${NAME_PREFIX}exercises" "nginx-validation"
  setPermission "${NAME_PREFIX}exercises" "nginx-validation" "${GITOPS_USERNAME}" "WRITE"

  addRepo "${NAME_PREFIX}exercises" "broken-application"
  setPermission "${NAME_PREFIX}exercises" "broken-application" "${GITOPS_USERNAME}" "WRITE"

  # Install necessary plugins
  installScmmPlugin "scm-mail-plugin" "false"
  installScmmPlugin "scm-review-plugin" "false"
  installScmmPlugin "scm-code-editor-plugin" "false"
  installScmmPlugin "scm-editor-plugin" "false"
  installScmmPlugin "scm-landingpage-plugin" "false"
  installScmmPlugin "scm-el-plugin" "false"
  installScmmPlugin "scm-jenkins-plugin" "false"
  installScmmPlugin "scm-readme-plugin" "false"
  installScmmPlugin "scm-webhook-plugin" "false"
  installScmmPlugin "scm-ci-plugin" "true"
  installScmmPlugin "scm-metrics-prometheus-plugin" "true"

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
    --data "{\"name\":\"${NAME}\",\"namespace\":\"${NAMESPACE}\",\"type\":\"git\",\"description\":\"${DESCRIPTION}\",\"contextEntries\":{},\"_links\":{}}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/repositories/?initialize=true") && EXIT_STATUS=$? || EXIT_STATUS=$?
  if [ $EXIT_STATUS != 0 ]; then
    echo "Adding Repo failed with exit code: curl: ${EXIT_STATUS}, HTTP Status: ${STATUS}"
    exit $EXIT_STATUS
  fi

  printStatus "${STATUS}"
}

function setConfig() {
  printf 'Setting config'

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H "Content-Type: application/vnd.scmm-config+json;v=2" \
    --data "{\"proxyPassword\":null,\"proxyPort\":8080,\"proxyServer\":\"proxy.mydomain.com\",\"proxyUser\":null,\"enableProxy\":false,\"realmDescription\":\"SONIA :: SCM Manager\",\"disableGroupingGrid\":false,\"dateFormat\":\"YYYY-MM-DD HH:mm:ss\",\"anonymousAccessEnabled\":false,\"anonymousMode\":\"OFF\",\"baseUrl\":\"${SCMM_BASE_URL}\",\"forceBaseUrl\":false,\"loginAttemptLimit\":-1,\"proxyExcludes\":[],\"skipFailedAuthenticators\":false,\"pluginUrl\":\"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}\",\"loginAttemptLimitTimeout\":300,\"enabledXsrfProtection\":true,\"namespaceStrategy\":\"CustomNamespaceStrategy\",\"loginInfoUrl\":\"https://login-info.scm-manager.org/api/v1/login-info\",\"releaseFeedUrl\":\"https://scm-manager.org/download/rss.xml\",\"mailDomainName\":\"scm-manager.local\",\"adminGroups\":[],\"adminUsers\":[]}" \
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

function setPermissionForUser() {
  printf 'Setting permission %s for %s... ' "${2}" "${1}"

  STATUS=$(curl -i -s -L -o /dev/null --write-out '%{http_code}' -X PUT -H "Content-Type: application/vnd.scmm-permissionCollection+json;v=2" \
    --data "{\"permissions\":[\"${2}\"]}" \
    "${SCMM_PROTOCOL}://${SCMM_USER}:${SCMM_PWD}@${SCMM_HOST}/api/v2/users/${1}/permissions") && EXIT_STATUS=$? || EXIT_STATUS=$?
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
  else
    echo "--set service.type=NodePort"
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

initSCMM "$@"