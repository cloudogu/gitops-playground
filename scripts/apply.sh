#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

BASEDIR=$(dirname $0)
export BASEDIR
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
export ABSOLUTE_BASEDIR
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"
export PLAYGROUND_DIR

PETCLINIC_COMMIT=949c5af
SPRING_BOOT_HELM_CHART_COMMIT=0.2.0

declare -A hostnames
hostnames[scmm]="localhost"
hostnames[jenkins]="localhost"
hostnames[argocd]="localhost"

declare -A ports
# get ports from values files
ports[scmm]=$(grep 'nodePort:' "${PLAYGROUND_DIR}"/scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')
ports[jenkins]=$(grep 'nodePort:' "${PLAYGROUND_DIR}"/jenkins/values.yaml | grep nodePort | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')
ports[argocd]=$(grep 'servicePortHttp:' "${PLAYGROUND_DIR}"/argocd/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')

source ${ABSOLUTE_BASEDIR}/utils.sh
source ${ABSOLUTE_BASEDIR}/jenkins/init-jenkins.sh
source ${ABSOLUTE_BASEDIR}/scm-manager/init-scmm.sh

function main() {
  DEBUG="${1}"
  INSTALL_ALL_MODULES="${2}"
  INSTALL_FLUXV1="${3}"
  INSTALL_FLUXV2="${4}"
  INSTALL_ARGOCD="${5}"
  REMOTE_CLUSTER="${6}"
  SET_USERNAME="${7}"
  SET_PASSWORD="${8}"
  JENKINS_URL="${9}"
  JENKINS_USERNAME="${10}"
  JENKINS_PASSWORD="${11}"
  REGISTRY_URL="${12}"
  REGISTRY_PATH="${13}"
  REGISTRY_USERNAME="${14}"
  REGISTRY_PASSWORD="${15}"
  SCMM_URL="${16}"
  SCMM_USERNAME="${17}"
  SCMM_PASSWORD="${18}"
  INSECURE="${19}"
  TRACE="${20}"
  
  if [[ $TRACE == 'true' ]]; then
    set -x
  fi

  if [[ $INSECURE == true ]]; then
    CURL_HOME="${PLAYGROUND_DIR}"
    export CURL_HOME
    export GIT_SSL_NO_VERIFY=1
  fi

  checkPrerequisites

  if [[ $DEBUG != true ]]; then
    backgroundLogFile=$(mktemp /tmp/playground-log-XXXXXXXXX.log)
    echo "Full log output is appended to ${backgroundLogFile}"
  fi

  evalWithSpinner "Basic setup & configuring registry..." applyBasicK8sResources

  initSCMM

  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_FLUXV1 = true ]]; then
    evalWithSpinner "Starting Flux V1..." initFluxV1
  fi
  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_FLUXV2 = true ]]; then
    evalWithSpinner "Starting Flux V2..." initFluxV2
  fi
  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_ARGOCD = true ]]; then
    evalWithSpinner "Starting ArgoCD..." initArgo
  fi

#  initJenkins

  if [[ $TRACE == true ]]; then
    set +x
  fi
  printWelcomeScreen
}

function evalWithSpinner() {

  spinnerOutput="${1}"
  shift
  function_args=("$@")

  commandToEval="$(printf "'%s' " "${function_args[@]}")"

  if [[ $DEBUG == true ]]; then
    eval "$commandToEval"
  else
    eval "$commandToEval" >>"${backgroundLogFile}" 2>&1 &
    spinner "${spinnerOutput}"
  fi
}

function checkPrerequisites() {
  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_ARGOCD == true ]]; then
    if ! command -v htpasswd &>/dev/null; then
      echo "Missing required command htpasswd"
      exit 1
    fi
  fi
}

function applyBasicK8sResources() {
  kubectl apply -f k8s-namespaces || true

  createSecrets

  helm repo add fluxcd https://charts.fluxcd.io
  helm repo add stable https://charts.helm.sh/stable
  helm repo add argo https://argoproj.github.io/argo-helm
  helm repo add bitnami https://charts.bitnami.com/bitnami
  helm repo add scm-manager https://packages.scm-manager.org/repository/helm-v2-releases/
  helm repo add jenkins https://charts.jenkins.io
  helm repo update

  initRegistry
}

function initRegistry() {
  if [[ -z "${REGISTRY_URL}" ]]; then
    helm upgrade -i docker-registry --values docker-registry/values.yaml --version 1.9.4 stable/docker-registry -n default
    REGISTRY_URL="localhost:30000"
  fi
}

function initJenkins() {
  if [[ -z "${JENKINS_URL}" ]]; then
    deployJenkinsCommand=(deployLocalJenkins "${SET_USERNAME}" "${SET_PASSWORD}" "${REMOTE_CLUSTER}")
    evalWithSpinner "Deploying Jenkins..." "${deployJenkinsCommand[@]}"

    setExternalHostnameIfNecessary "jenkins" "jenkins" "default"
    JENKINS_URL=$(createUrl "jenkins")

    configureJenkinsCommand=(configureJenkins "${JENKINS_URL}" "${SET_USERNAME}" "${SET_PASSWORD}" \
                                              "${SCMM_URL}" "${SCMM_PASSWORD}" "${REGISTRY_URL}" \
                                              "${REGISTRY_PATH}" "${REGISTRY_USERNAME}" "${REGISTRY_PASSWORD}")
    evalWithSpinner "Configuring Jenkins..." "${configureJenkinsCommand[@]}"
  else
    configureJenkinsCommand=(configureJenkins "${JENKINS_URL}" "${JENKINS_USERNAME}" "${JENKINS_PASSWORD}" \
                                              "${SCMM_URL}" "${SCMM_PASSWORD}" "${REGISTRY_URL}" \
                                              "${REGISTRY_PATH}" "${REGISTRY_USERNAME}" "${REGISTRY_PASSWORD}")
    evalWithSpinner "Configuring Jenkins..." "${configureJenkinsCommand[@]}"
  fi
}

function initSCMM() {
  if [[ -z "${SCMM_URL}" ]]; then
    SCMM_USERNAME=${SET_USERNAME}
    SCMM_PASSWORD=${SET_PASSWORD}

    deployScmmCommand=(deployLocalScmmManager "${REMOTE_CLUSTER}")
    evalWithSpinner "Deploying SCMM-Manager ..." "${deployScmmCommand[@]}"

    configureScmmCommand=(configureScmmManager "${SCMM_USERNAME}" "${SCMM_PASSWORD}" "http://${hostnames[scmm]}:${ports[scmm]}" "http://jenkins" "true")
    evalWithSpinner "Configuring SCM-Manager ..." "${configureScmmCommand[@]}"

    SCMM_URL="http://scmm-scm-manager"

    # We need to query remote IP here (in the main process) again, because the "initSCMM" methods might be running in a
    # background process (to display the spinner only)
    setExternalHostnameIfNecessary 'scmm' 'scmm-scm-manager' 'default'
  else
    configureScmmCommand=(configureScmmManager "${SCMM_USERNAME}" "${SCMM_PASSWORD}" "${SCMM_URL}" "$(createUrl jenkins)" "false")
    evalWithSpinner "Configuring SCM-Manager ..." "${configureScmmCommand[@]}"
  fi

  pushHelmChartRepo 'common/spring-boot-helm-chart'
  pushRepoMirror 'https://github.com/cloudogu/gitops-build-lib.git' 'common/gitops-build-lib'
  pushRepoMirror 'https://github.com/cloudogu/ces-build-lib.git' 'common/ces-build-lib' 'develop'
}

function setExternalHostnameIfNecessary() {
  hostKey="$1"
  serviceName="$2"
  namespace="$3"
  if [[ $REMOTE_CLUSTER == true ]]; then
    hostnames[${hostKey}]=$(getExternalIP "${serviceName}" "${namespace}")
    ports[${hostKey}]=80
  fi
}

function initFluxV1() {
  initRepo 'fluxv1/gitops'
  pushPetClinicRepo 'applications/petclinic/fluxv1/plain-k8s' 'fluxv1/petclinic-plain'
  pushPetClinicRepo 'applications/petclinic/fluxv1/helm' 'fluxv1/petclinic-helm'
  initRepoWithSource 'applications/nginx/fluxv1' 'fluxv1/nginx-helm'

  # shellcheck disable=SC2016
  # we don't want to expand $(username):$(password) here, it will be used inside the flux-operator
  helm upgrade -i flux-operator --values fluxv1/flux-operator/values.yaml \
               --set git.url="${SCMM_PROTOCOL}"'://$(username):$(password)@'"${SCMM_HOST}"'/scm/repo/fluxv1/gitops'\
               --version 1.3.0 fluxcd/flux -n fluxv1
  helm upgrade -i helm-operator --values fluxv1/helm-operator/values.yaml --version 1.2.0 fluxcd/helm-operator -n fluxv1
}

function initFluxV2() {
  pushPetClinicRepo 'applications/petclinic/fluxv2/plain-k8s' 'fluxv2/petclinic-plain'

  initRepoWithSource 'fluxv2' 'fluxv2/gitops' "$(buildScmmUrlReplaceCmd 'clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml' "-i")"


  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-components.yaml || true
  kubectl apply -f "$(mkTmpWithReplacedScmmUrls "fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml")" || true
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-kustomization.yaml || true

}

function buildScmmUrlReplaceCmd() {
  SED_PARAMS="${2:-""}"
  echo 'sed '"${SED_PARAMS}"' -e '"s:scmm-scm-manager.default.svc.cluster.local:${SCMM_HOST}:g"' -e '"s:http:${SCMM_PROTOCOL}:g ${1}"
}

function mkTmpWithReplacedScmmUrls() {
  REPLACE_FILE="${1}"
  TMP_FILENAME=$(mktemp /tmp/scmm-replace.XXXXXX)

  REPLACE_CMD=$(buildScmmUrlReplaceCmd "${REPLACE_FILE}")
  eval "${REPLACE_CMD}" > "${TMP_FILENAME}"

  echo "${TMP_FILENAME}"
}

function initArgo() {
  helm upgrade -i argocd --values argocd/values.yaml \
    $(argoHelmSettingsForRemoteCluster) --version 2.9.5 argo/argo-cd -n argocd

  BCRYPT_PW=$(bcryptPassword "${SET_PASSWORD}")
  # set argocd admin password to 'admin' here, because it does not work through the helm chart
  kubectl patch secret -n argocd argocd-secret -p '{"stringData": { "admin.password": "'"${BCRYPT_PW}"'"}}' || true

  pushPetClinicRepo 'applications/petclinic/argocd/plain-k8s' 'argocd/petclinic-plain'
  initRepo 'argocd/gitops'
  initRepoWithSource 'applications/nginx/argocd' 'argocd/nginx-helm'
  initRepoWithSource 'argocd/control-app' 'argocd/control-app'
}

function argoHelmSettingsForRemoteCluster() {
  if [[ $REMOTE_CLUSTER == true ]]; then
    # Can't set service nodePort for argo, so use normal service ports for both local and remote
    echo '--set server.service.servicePortHttp=80 --set server.service.servicePortHttps=443'
  fi
}

function createSecrets() {
  createSecret scmm-credentials --from-literal=USERNAME=$SET_USERNAME --from-literal=PASSWORD=$SET_PASSWORD -n default

  createSecret gitops-scmm --from-literal=USERNAME=gitops --from-literal=PASSWORD=$SET_PASSWORD -n default
  createSecret gitops-scmm --from-literal=USERNAME=gitops --from-literal=PASSWORD=$SET_PASSWORD -n argocd
  # flux needs lowercase fieldnames
  createSecret gitops-scmm --from-literal=username=gitops --from-literal=password=$SET_PASSWORD -n fluxv1
  createSecret gitops-scmm --from-literal=username=gitops --from-literal=password=$SET_PASSWORD -n fluxv2
}

function createSecret() {
  kubectl create secret generic "$@" --dry-run=client -oyaml | kubectl apply -f-
}

function pushPetClinicRepo() {
  LOCAL_PETCLINIC_SOURCE="$1"
  TARGET_REPO_SCMM="$2"

  TMP_REPO=$(mktemp -d)

  git clone -n https://github.com/cloudogu/spring-petclinic.git "${TMP_REPO}" --quiet >/dev/null 2>&1
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${PETCLINIC_COMMIT} --quiet

    cp -r "${PLAYGROUND_DIR}/${LOCAL_PETCLINIC_SOURCE}"/* .
    git checkout -b main --quiet
    git add .
    git commit -m 'Add GitOps Pipeline and K8s resources' --quiet

    waitForScmManager
    git push -u "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function pushHelmChartRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)
  git clone -n https://github.com/cloudogu/spring-boot-helm-chart.git "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${SPRING_BOOT_HELM_CHART_COMMIT} --quiet
    # Create a defined version to use in demo applications
    git tag 1.0.0

    git branch -d main
    git checkout -b main

    waitForScmManager
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet --force
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
    git push --mirror "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" --force --quiet
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}" "${DEFAULT_BRANCH}"
}

# TODO: either local or external
function waitForScmManager() {
  echo -n "Waiting for SCM-Manager to become available at ${SCMM_PROTOCOL}://${SCMM_HOST}/scm"
  HTTP_CODE="0"
  while [[ "${HTTP_CODE}" -ne "200" ]]; do
    HTTP_CODE="$(curl -s -L -o /dev/null --max-time 10 -w ''%{http_code}'' "${SCMM_PROTOCOL}://${SCMM_HOST}/scm")" || true
    echo -n "."
    sleep 2
  done
}

function initRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)

  git clone "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    git checkout main --quiet || git checkout -b main --quiet
    echo "# gitops" >README.md
    echo $'.*\n!/.gitignore' >.gitignore
    git add README.md .gitignore
    # exits with 1 if there were differences and 0 means no differences.
    if ! git diff-index --exit-code --quiet HEAD --; then
      git commit -m "Add readme" --quiet
    fi
    waitForScmManager
    git push -u "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function initRepoWithSource() {
  echo "initiating repo $1 with source $2"
  SOURCE_REPO="$1"
  TARGET_REPO_SCMM="$2"
  MANIPULATE_SOURCES=${3:-"$()"}

  TMP_REPO=$(mktemp -d)

  git clone "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    cp -r "${PLAYGROUND_DIR}/${SOURCE_REPO}"/* .
    $MANIPULATE_SOURCES
    git checkout main --quiet || git checkout -b main --quiet
    git add .
    git commit -m "Init ${TARGET_REPO_SCMM}" --quiet || true
    waitForScmManager
    git push -u "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function setDefaultBranch() {
  TARGET_REPO_SCMM="$1"
  DEFAULT_BRANCH="${2:-main}"

  curl -s -L -X PUT -H 'Content-Type: application/vnd.scmm-gitConfig+json' \
    --data-raw "{\"defaultBranch\":\"${DEFAULT_BRANCH}\"}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/scm/api/v2/config/git/${TARGET_REPO_SCMM}"
}

function createUrl() {
  systemName=${1}
  hostname=${hostnames[${systemName}]}
  port=${ports[${systemName}]}

  if [[ "${systemName}" == "jenkins" && -n "${JENKINS_URL}" ]]; then
    echo "${JENKINS_URL}"
    return
  elif [[ "${systemName}" == "scmm" && -n "${SCMM_URL}" ]]; then
    echo "${SCMM_URL}"
    return
  fi

  if [[ -z "${port}" ]]; then
    error "hostname ${systemName} not defined"
    exit 1
  fi

  echo -n "http://${hostname}"
  [[ "${port}" != "80" && "${port}" != "443" ]] && echo -n ":${port}"
}

function printWelcomeScreen() {

  if [[ -z "${JENKINS_URL}" ]]; then
    setExternalHostnameIfNecessary 'jenkins' 'jenkins' 'default'
  fi

  echo
  echo
  echo "|----------------------------------------------------------------------------------------------|"
  echo "|                     ☁️  Welcome to the GitOps playground by Cloudogu! ☁️                       |"
  echo "|----------------------------------------------------------------------------------------------|"
  echo "|"
  echo "| The playground features three example applications (Sprint PetClinic - one for every gitops solution) in SCM-Manager."
  echo "| See here:"
  echo "|"
  echo -e "| - \e[32m$(createUrl scmm)/scm/repo/fluxv1/petclinic-plain/code/sources/main/\e[0m"
  echo -e "| - \e[32m$(createUrl scmm)/scm/repo/fluxv2/petclinic-plain/code/sources/main/\e[0m"
  echo -e "| - \e[32m$(createUrl scmm)/scm/repo/argocd/petclinic-plain/code/sources/main/\e[0m"
  echo "|"
  echo -e "| Credentials for SCM-Manager and Jenkins are: \e[31m${SET_USERNAME}/${SET_PASSWORD}\e[0m"
  echo "|"
  echo "| Once Jenkins is up, the following jobs can be started after scanning the corresponding namespace via the jenkins UI:"
  echo "|"
  echo -e "| - \e[32m$(createUrl jenkins)/job/fluxv1-applications/\e[0m"
  echo -e "| - \e[32m$(createUrl jenkins)/job/fluxv2-applications/\e[0m"
  echo -e "| - \e[32m$(createUrl jenkins)/job/argocd-applications/\e[0m"
  echo "|"
  echo "| During the job, jenkins pushes into the corresponding GitOps repo and creates a pull request for production:"
  echo "|"

  printWelcomeScreenFluxV1

  printWelcomeScreenFluxV2

  printWelcomeScreenArgocd

  echo "| After a successful Jenkins build, the staging application will be deployed into the cluster."
  echo "|"
  echo "| The production applications can be deployed by accepting Pull Requests."
  echo "| After about 1 Minute after the PullRequest has been accepted, the GitOps operator deploys to production."
  echo "|"
  echo "| Please see the README.md for how to find out the URLs of the individual applications."
  echo "|"
  echo "|----------------------------------------------------------------------------------------------|"
}

function printWelcomeScreenFluxV1() {
  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_FLUXV1 = true ]]; then
    echo "| For Flux V1:"
    echo "|"
    echo -e "| - GitOps repo: \e[32m$(createUrl scmm)/scm/repo/fluxv1/gitops/code/sources/main/\e[0m"
    echo -e "| - Pull requests: \e[32m$(createUrl scmm)/scm/repo/fluxv1/gitops/pull-requests\e[0m"
    echo "|"
  fi
}

function printWelcomeScreenFluxV2() {
  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_FLUXV2 = true ]]; then
    echo "| For Flux V2:"
    echo "|"
    echo -e "| - GitOps repo: \e[32m$(createUrl scmm)/scm/repo/fluxv2/gitops/code/sources/main/\e[0m"
    echo -e "| - Pull requests: \e[32m$(createUrl scmm)/scm/repo/fluxv2/gitops/pull-requests\e[0m"
    echo "|"
  fi
}

function printWelcomeScreenArgocd() {

  setExternalHostnameIfNecessary 'argocd' 'argocd-server' 'argocd'

  if [[ $INSTALL_ALL_MODULES == true || $INSTALL_ARGOCD == true ]]; then
    echo "| For ArgoCD:"
    echo "|"
    echo -e "| - GitOps repo: \e[32m$(createUrl scmm)/scm/repo/argocd/gitops/code/sources/main/\e[0m"
    echo -e "| - Pull requests: \e[32m$(createUrl scmm)/scm/repo/argocd/gitops/pull-requests\e[0m"
    echo "|"
    echo -e "| There is also the ArgoCD UI which can be found at \e[32mhttp://$(createUrl argocd)/\e[0m"
    echo -e "| Credentials for the ArgoCD UI are: \e[31m${SET_USERNAME}/${SET_PASSWORD}\e[0m"
    echo "|"
  fi
}

function printUsage() {
  echo "This script will install all necessary resources for Flux V1, Flux V2 and ArgoCD into your k8s-cluster."
  echo ""
  printParameters
  echo ""
}

function printParameters() {
  echo "The following parameters are valid:"
  echo
  echo " -h | --help     >> Help screen"
  echo
  echo "Install only the desired GitOps modules. Multiple selections possible."
  echo "    | --fluxv1   >> Install the Flux V1 module"
  echo "    | --fluxv2   >> Install the Flux V2 module"
  echo "    | --argocd   >> Install the ArgoCD module"
  echo
  echo "    | --remote   >> Install on remote Cluster e.g. gcp"
  echo
  echo "    | --password=myPassword   >> Set initial admin passwords to 'myPassword'"
  echo
  echo "Configure external jenkins. Use this 3 parameters to configure an external jenkins"
  echo "    | --jenkins-url=http://jenkins   >> The url of your external jenkins"
  echo "    | --jenkins-username=myUsername  >> Mandatory when --jenkins-url is set"
  echo "    | --jenkins-password=myPassword  >> Mandatory when --jenkins-url is set"
  echo
  echo "Configure external scm-manager. Use this 3 parameters to configure an external scmm"
  echo "    | --scmm-url=http://scm-manager:8080   >> The host of your external scm-manager"
  echo "    | --scmm-username=myUsername  >> Mandatory when --scmm-url is set"
  echo "    | --scmm-password=myPassword  >> Mandatory when --scmm-url is set"
  echo
  echo "Configure external docker registry. Use this 4 parameters to configure an external docker registry"
  echo "    | --registry-url=registry         >> The url of your external registry"
  echo "    | --registry-path=public          >> Optional when --registry-url is set"
  echo "    | --registry-username=myUsername  >> Optional when --registry-url is set"
  echo "    | --registry-password=myPassword  >> Optional when --registry-url is set"
  echo
  echo " -w | --welcome  >> Welcome screen"
  echo
  echo " -d | --debug    >> Debug output"
  echo " -x | --trace    >> Show each command executed; set -x"
}

COMMANDS=$(getopt \
  -o hwdx \
  --long help,fluxv1,fluxv2,argocd,welcome,debug,remote,username:,password:,jenkins-url:,jenkins-username:,jenkins-password:,registry-url:,registry-path:,registry-username:,registry-password:,scmm-url:,scmm-username:,scmm-password:,trace,insecure \
  -- "$@")

if [ $? != 0 ] ; then echo "Terminating..." >&2 ; exit 1 ; fi

eval set -- "$COMMANDS"

DEBUG=false
INSTALL_ALL_MODULES=true
INSTALL_FLUXV1=false
INSTALL_FLUXV2=false
INSTALL_ARGOCD=false
REMOTE_CLUSTER=false
SET_USERNAME="admin"
SET_PASSWORD="admin"
JENKINS_URL=""
JENKINS_USERNAME=""
JENKINS_PASSWORD=""
REGISTRY_URL=""
REGISTRY_PATH=""
REGISTRY_USERNAME=""
REGISTRY_PASSWORD=""
SCMM_URL=""
SCMM_USERNAME=""
SCMM_PASSWORD=""
INSECURE=false
TRACE=false

while true; do
  case "$1" in
    -h | --help          ) printUsage; exit 0 ;;
    --fluxv1             ) INSTALL_FLUXV1=true; INSTALL_ALL_MODULES=false; shift ;;
    --fluxv2             ) INSTALL_FLUXV2=true; INSTALL_ALL_MODULES=false; shift ;;
    --argocd             ) INSTALL_ARGOCD=true; INSTALL_ALL_MODULES=false; shift ;;
    --remote             ) REMOTE_CLUSTER=true; shift ;;
    --jenkins-url        ) JENKINS_URL="$2"; shift 2 ;;
    --jenkins-username   ) JENKINS_USERNAME="$2"; shift 2 ;;
    --jenkins-password   ) JENKINS_PASSWORD="$2"; shift 2 ;;
    --registry-url       ) REGISTRY_URL="$2"; shift 2 ;;
    --registry-path      ) REGISTRY_PATH="$2"; shift 2 ;;
    --registry-username  ) REGISTRY_USERNAME="$2"; shift 2 ;;
    --registry-password  ) REGISTRY_PASSWORD="$2"; shift 2 ;;
    --scmm-url           ) SCMM_URL="$2"; shift 2 ;;
    --scmm-username      ) SCMM_USERNAME="$2"; shift 2 ;;
    --scmm-password      ) SCMM_PASSWORD="$2"; shift 2 ;;
    --insecure           ) INSECURE=true; shift ;;
    --username           ) SET_USERNAME="$2"; shift 2 ;;
    --password           ) SET_PASSWORD="$2"; shift 2 ;;
    -w | --welcome       ) printWelcomeScreen; exit 0 ;;
    -d | --debug         ) DEBUG=true; shift ;;
    -x | --trace         ) TRACE=true; shift ;;
    --                   ) shift; break ;;
  *) break ;;
  esac
done

confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
  exit 0

main "$DEBUG" "$INSTALL_ALL_MODULES" "$INSTALL_FLUXV1" "$INSTALL_FLUXV2" "$INSTALL_ARGOCD" "$REMOTE_CLUSTER" "$SET_USERNAME" "$SET_PASSWORD" "$JENKINS_URL" "$JENKINS_USERNAME" "$JENKINS_PASSWORD" "$REGISTRY_URL" "$REGISTRY_PATH" "$REGISTRY_USERNAME" "$REGISTRY_PASSWORD" "$SCMM_URL" "$SCMM_USERNAME" "$SCMM_PASSWORD" "$INSECURE" "$TRACE"