#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"

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

function main() {
  DEBUG=$1
  INSTALL_ALL_MODULES=$2
  INSTALL_FLUXV1=$3
  INSTALL_FLUXV2=$4
  INSTALL_ARGOCD=$5
  REMOTE_CLUSTER=$6
  SET_USERNAME=$7
  SET_PASSWORD=$8

  checkPrerequisites

  if [[ $DEBUG != true ]]; then
    backgroundLogFile=$(mktemp /tmp/playground-log-XXXXXXXXX.log)
    echo "Full log output is appended to ${backgroundLogFile}"
  fi

  evalWithSpinner applyBasicK8sResources "Basic setup & starting registry..."
  evalWithSpinner initSCMM "Starting SCM-Manager..."

  # We need to query remote IP here (in the main process) again, because the "initSCMM" methods might be running in a
  # background process (to display the spinner only)
  setExternalHostnameIfNecessary 'scmm' 'scmm-scm-manager' 'default'

  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_FLUXV1 = true ]]; then
    evalWithSpinner initFluxV1 "Starting Flux V1..."
  fi
  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_FLUXV2 = true ]]; then
    evalWithSpinner initFluxV2 "Starting Flux V2..."
  fi
  if [[ $INSTALL_ALL_MODULES = true || $INSTALL_ARGOCD = true ]]; then
    evalWithSpinner initArgo "Starting ArgoCD..."
  fi

  # Start Jenkins last, so all repos have been initialized when repo indexing starts
  evalWithSpinner initJenkins "Starting Jenkins..."

  printWelcomeScreen
}

function evalWithSpinner() {
  commandToEval=$1
  spinnerOutput=$2
  
  if [[ $DEBUG == true ]]; then
    eval "$commandToEval"
  else 
    eval "$commandToEval" >> "${backgroundLogFile}" 2>&1 & spinner "${spinnerOutput}"
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
  # Mark the first node for Jenkins and agents. See jenkins/values.yamls "agent.workingDir" for details.
  # Remove first (in case new nodes were added)
  kubectl label --all nodes node- >/dev/null
  kubectl label $(kubectl get node -o name | sort | head -n 1) node=jenkins

  kubectl apply -f k8s-namespaces || true

  createSecrets

  kubectl apply -f jenkins/resources || true

  helm repo add jenkins https://charts.jenkins.io
  helm repo add fluxcd https://charts.fluxcd.io
  helm repo add stable https://charts.helm.sh/stable
  helm repo add argo https://argoproj.github.io/argo-helm
  helm repo add bitnami https://charts.bitnami.com/bitnami

  helm upgrade -i docker-registry --values docker-registry/values.yaml --version 1.9.4 stable/docker-registry -n default
}

function initSCMM() {
  helm upgrade -i scmm --values scm-manager/values.yaml \
    --set-file=postStartHookScript=scm-manager/initscmm.sh \
    $(scmmHelmSettingsForRemoteCluster) scm-manager/chart -n default

  setExternalHostnameIfNecessary 'scmm' 'scmm-scm-manager' 'default'
  
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

function scmmHelmSettingsForRemoteCluster() {
  if [[ $REMOTE_CLUSTER == true ]]; then
    # Default clusters don't allow for node ports < 30.000, so just unset nodePort.
    # A defined nodePort is not needed for remote cluster, where the externalIp is used for accessing SCMM
    echo "--set service.nodePort="
  fi
}

function initJenkins() {
  # Find out the docker group and put the agent into it. Otherwise it has no permission to access  the docker host.
  helm upgrade -i jenkins --values jenkins/values.yaml \
    $(jenkinsHelmSettingsForLocalCluster) --set agent.runAsGroup=$(queryDockerGroupOfJenkinsNode) \
    --version 2.13.0 jenkins/jenkins -n default
}

function queryDockerGroupOfJenkinsNode() {
  kubectl apply -f jenkins/tmp-docker-gid-grepper.yaml >/dev/null
  until kubectl get po --field-selector=status.phase=Running | grep tmp-docker-gid-grepper >/dev/null; do
    sleep 1
  done

  kubectl exec tmp-docker-gid-grepper -- cat /etc/group | grep docker | cut -d: -f3

  # This call might block some (unnecessary) seconds so move to background
  kubectl delete -f jenkins/tmp-docker-gid-grepper.yaml >/dev/null &
}

function jenkinsHelmSettingsForLocalCluster() {
  if [[ $REMOTE_CLUSTER != true ]]; then
    # Run Jenkins and Agent pods as the current user.
    # Avoids file permission problems when accessing files on the host that were written from the pods

    # We also need a host port, so jenkins can be reached via localhost:9090
    # But: This helm charts only uses the nodePort value, if the type is "NodePort". So change it for local cluster.
    echo "--set master.runAsUser=$(id -u) --set agent.runAsUser=$(id -u)" \
      "--set master.serviceType=NodePort" 
  fi
}

function initFluxV1() {
  initRepo 'fluxv1/gitops'
  pushPetClinicRepo 'applications/petclinic/fluxv1/plain-k8s' 'fluxv1/petclinic-plain'
  pushPetClinicRepo 'applications/petclinic/fluxv1/helm' 'fluxv1/petclinic-helm'
  initRepoWithSource 'applications/nginx/fluxv1' 'fluxv1/nginx-helm'

  helm upgrade -i flux-operator --values fluxv1/flux-operator/values.yaml --version 1.3.0 fluxcd/flux -n fluxv1
  helm upgrade -i helm-operator --values fluxv1/helm-operator/values.yaml --version 1.2.0 fluxcd/helm-operator -n fluxv1
}

function initFluxV2() {
  pushPetClinicRepo 'applications/petclinic/fluxv2/plain-k8s' 'fluxv2/petclinic-plain'
  initRepoWithSource 'fluxv2' 'fluxv2/gitops'

  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-components.yaml || true
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml || true
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-kustomization.yaml || true

}

function initArgo() {
  helm upgrade -i argocd --values argocd/values.yaml \
    $(argoHelmSettingsForRemoteCluster) --version 2.9.5 argo/argo-cd -n argocd

  kubectl apply -f argocd/resources -n argocd || true

  BCRYPT_PW=$(bcryptPassword "${SET_PASSWORD}")
  # set argocd admin password to 'admin' here, because it does not work through the helm chart
  kubectl patch secret -n argocd argocd-secret -p '{"stringData": { "admin.password": "'${BCRYPT_PW}'"}}' || true

  pushPetClinicRepo 'applications/petclinic/argocd/plain-k8s' 'argocd/petclinic-plain'
  initRepo 'argocd/gitops'
  initRepoWithSource 'applications/nginx/argocd' 'argocd/nginx-helm'
}

function argoHelmSettingsForRemoteCluster() {
  if [[ $REMOTE_CLUSTER == true ]]; then
    # Can't set service nodePort for argo, so use normal service ports for both local and remote
    echo '--set server.service.servicePortHttp=80 --set server.service.servicePortHttps=443'
  fi
}

function createSecrets() {
  createSecret scmm-credentials --from-literal=USERNAME=$SET_USERNAME --from-literal=PASSWORD=$SET_PASSWORD -n default
  createSecret jenkins-credentials --from-literal=jenkins-admin-user=$SET_USERNAME --from-literal=jenkins-admin-password=$SET_PASSWORD -n default

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
    git push -u "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
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
    git push "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet --force
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
    git push --mirror "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" --force --quiet
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}" "${DEFAULT_BRANCH}"
}

function waitForScmManager() {
  echo -n "Waiting for SCM-Manager to become available at http://${hostnames[scmm]}:${ports[scmm]}/scm"
  while [[ "$(curl -s -L -o /dev/null -w ''%{http_code}'' "http://${hostnames[scmm]}:${ports[scmm]}/scm")" -ne "200" ]]; do
    echo -n .
    sleep 2
  done
}

function initRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    git checkout main --quiet || git checkout -b main --quiet
    echo "# gitops" > README.md
    echo $'.*\n!/.gitignore' > .gitignore
    git add README.md .gitignore
    # exits with 1 if there were differences and 0 means no differences.
    if ! git diff-index --exit-code --quiet HEAD --; then
      git commit -m "Add readme" --quiet
    fi
    waitForScmManager
    git push -u "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function initRepoWithSource() {
  echo "initiating repo $1 with source $2"
  SOURCE_REPO="$1"
  TARGET_REPO_SCMM="$2"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    cp -r "${PLAYGROUND_DIR}/${SOURCE_REPO}"/* .
    git checkout main --quiet || git checkout -b main --quiet
    git add .
    git commit -m "Init ${TARGET_REPO_SCMM}" --quiet || true
    waitForScmManager
    git push -u "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function setDefaultBranch() {
  TARGET_REPO_SCMM="$1"
  DEFAULT_BRANCH="${2:-main}"

  curl -s -L -X PUT -H 'Content-Type: application/vnd.scmm-gitConfig+json' \
    --data-raw "{\"defaultBranch\":\"${DEFAULT_BRANCH}\"}" \
    "http://${SET_USERNAME}:${SET_PASSWORD}@${hostnames[scmm]}:${ports[scmm]}/scm/api/v2/config/git/${TARGET_REPO_SCMM}"
}

function createUrl() {
  systemName=$1
  hostname=${hostnames[${systemName}]}
  port=${ports[${systemName}]}

  if [[ -z "${port}" ]]; then
    error "hostname ${systemName} not defined"
    exit 1
  fi

  echo -n "http://${hostname}"
  [[ "${port}" != "80" && "${port}" != "443" ]] && echo -n ":${port}"
}

function printWelcomeScreen() {
  
  setExternalHostnameIfNecessary 'jenkins' 'jenkins' 'default'

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
  echo "| Once Jenkins is up, the following jobs will be running:"
  echo "|"
  echo -e "| - \e[32m$(createUrl jenkins)/job/fluxv1-nginx/\e[0m"
  echo -e "| - \e[32m$(createUrl jenkins)/job/fluxv1-petclinic-plain/\e[0m"
  echo -e "| - \e[32m$(createUrl jenkins)/job/fluxv2-petclinic-plain/\e[0m"
  echo -e "| - \e[32m$(createUrl jenkins)/job/argocd-petclinic-plain/\e[0m"
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
    echo " -w | --welcome  >> Welcome screen"
    echo
    echo " -d | --debug    >> Debug output"
}

COMMANDS=$(getopt \
                -o hwd \
                --long help,fluxv1,fluxv2,argocd,welcome,debug,remote,username:,password: \
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
while true; do
  case "$1" in
    -h | --help     ) printUsage; exit 0 ;;
    --fluxv1        ) INSTALL_FLUXV1=true; INSTALL_ALL_MODULES=false; shift ;;
    --fluxv2        ) INSTALL_FLUXV2=true; INSTALL_ALL_MODULES=false; shift ;;
    --argocd        ) INSTALL_ARGOCD=true; INSTALL_ALL_MODULES=false; shift ;;
    --remote        ) REMOTE_CLUSTER=true; shift ;;
    --password      ) SET_PASSWORD="$2"; shift 2 ;;
    -w | --welcome  ) printWelcomeScreen; exit 0 ;;
    -d | --debug    ) DEBUG=true; shift ;;
    --              ) shift; break ;;
    *               ) break ;;
  esac
done

confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
  exit 0

main $DEBUG $INSTALL_ALL_MODULES $INSTALL_FLUXV1 $INSTALL_FLUXV2 $INSTALL_ARGOCD $REMOTE_CLUSTER $SET_USERNAME $SET_PASSWORD

