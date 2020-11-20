#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

SCM_USER=scmadmin
SCM_PWD=scmadmin

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"

PETCLINIC_COMMIT=949c5af
# get scm-manager port from values
SCMM_PORT=$(grep -A1 'service:' "${PLAYGROUND_DIR}"/scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')

PETCLINIC_COMMIT=949c5af
# get scm-manager port from values
SCMM_PORT=$(grep -A1 'service:' scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')

source ${ABSOLUTE_BASEDIR}/utils.sh

function main() {

  applyK8sResources

  pushHelmChartRepo 'common/spring-boot-helm-chart'

  initRepo 'fluxv1/gitops'
  pushPetClinicRepo 'applications/petclinic/fluxv1/plain-k8s' 'fluxv1/petclinic-plain'

  initRepoWithSource 'fluxv2/gitops' 'fluxv2'
  pushPetClinicRepo 'applications/petclinic/fluxv2/plain-k8s' 'fluxv2/petclinic-plain'

  initRepo 'argocd/gitops'
  initRepoWithSource 'argocd/nginx-helm' 'applications/nginx'
  pushPetClinicRepo 'applications/petclinic/argocd/plain-k8s' 'argocd/petclinic-plain'
}

function applyK8sResources() {
  kubectl apply -f k8s-namespaces
#  & spinner "Creating namespaces"


  createScmmSecrets

  kubectl apply -f jenkins/resources
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-components.yaml

  helm repo add jenkins https://charts.jenkins.io
  helm repo add fluxcd https://charts.fluxcd.io
  helm repo add helm-stable https://charts.helm.sh/stable
  helm repo add argo https://argoproj.github.io/argo-helm

  helm upgrade -i scmm --values scm-manager/values.yaml --set-file=postStartHookScript=scm-manager/initscmm.sh scm-manager/chart -n default
  helm upgrade -i jenkins --values jenkins/values.yaml --version 2.13.0 jenkins/jenkins -n default
  helm upgrade -i flux-operator --values fluxv1/flux-operator/values.yaml --version 1.3.0 fluxcd/flux -n fluxv1
  helm upgrade -i helm-operator --values fluxv1/helm-operator/values.yaml --version 1.0.2 fluxcd/helm-operator -n fluxv1
  helm upgrade -i docker-registry --values docker-registry/values.yaml --version 1.9.4 helm-stable/docker-registry -n default


  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-kustomization.yaml

  helm upgrade -i argocd --values argocd/values.yaml --version 2.9.5 argo/argo-cd  -n argocd
  kubectl apply -f argocd/resources -n argocd

  # set argocd admin password to 'admin' here, because it does not work through the helm chart
  kubectl patch secret -n argocd argocd-secret -p '{"stringData": { "admin.password": "$2y$10$GsLZ7KlAhW9xNsb10YO3/O6jlJKEAU2oUrBKtlF/g1wVlHDJYyVom"}}'
}

function createScmmSecrets() {
  kubectl create secret generic gitops-scmm --from-literal=USERNAME=gitops --from-literal=PASSWORD=somePassword -n default || true
  kubectl create secret generic gitops-scmm --from-literal=USERNAME=gitops --from-literal=PASSWORD=somePassword -n fluxv1 || true
  # fluxv2 needs lowercase fieldnames
  kubectl create secret generic gitops-scmm --from-literal=username=gitops --from-literal=password=somePassword -n fluxv2 || true
  kubectl create secret generic gitops-scmm --from-literal=USERNAME=gitops --from-literal=PASSWORD=somePassword -n argocd || true
}

function pushPetClinicRepo() {
  LOCAL_PETCLINIC_SOURCE="$1"
  TARGET_REPO_SCMM="$2"

  TMP_REPO=$(mktemp -d)

  git clone -n https://github.com/cloudogu/spring-petclinic.git "${TMP_REPO}" --quiet > /dev/null 2>&1
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${PETCLINIC_COMMIT} --quiet

    cp -r "${PLAYGROUND_DIR}/${LOCAL_PETCLINIC_SOURCE}"/* .
    git checkout -b main --quiet
    git add .
    git commit -m 'Add GitOps Pipeline and K8s resources' --quiet

    waitForScmManager
    git push -u "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
  )

  rm -rf "${TMP_REPO}"
  
  setMainBranch "${TARGET_REPO_SCMM}"
}

function pushHelmChartRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)

  git clone -n https://github.com/cloudogu/spring-boot-helm-chart.git "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    git tag 1.0.0

    waitForScmManager
    git push "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet
  )

  rm -rf "${TMP_REPO}"

  setMainBranch "${TARGET_REPO_SCMM}"
}

function waitForScmManager() {
#  echo -n "Waiting for SCM-Manager to become available at http://localhost:${SCMM_PORT}/scm"
  while [[ "$(curl -s -L -o /dev/null -w ''%{http_code}'' "http://localhost:${SCMM_PORT}/scm")" -ne "200" ]]; do
#    echo -n .
    sleep 2
  done
}

function initRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    git checkout -b main  --quiet || true
    git checkout main --quiet || true
    echo "# gitops" > README.md
    git add README.md
    git commit -m "Add readme" --quiet || true
    waitForScmManager
    git push -u "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  setMainBranch "${TARGET_REPO_SCMM}"
}

function initRepoWithSource() {
  TARGET_REPO_SCMM="$1"
  SOURCE_REPO="$2"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cp -r "${PLAYGROUND_DIR}/${SOURCE_REPO}"/* "${TMP_REPO}"
    cd "${TMP_REPO}"
    git checkout -b main  --quiet || true
    git checkout main --quiet || true
    git add .
    git commit -m "Init ${TARGET_REPO_SCMM}" --quiet || true
    waitForScmManager
    git push -u "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  rm -rf "${TMP_REPO}"

  setMainBranch "${TARGET_REPO_SCMM}"
}

function setMainBranch() {
  TARGET_REPO_SCMM="$1"
  
  curl -s -L -X PUT -H 'Content-Type: application/vnd.scmm-gitConfig+json' \
    --data-raw "{\"defaultBranch\":\"main\"}" \
    "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/api/v2/config/git/${TARGET_REPO_SCMM}"
}

function printWelcomeScreen() {
  echo
  echo
  echo "***********************************************************************************************************************************"
  echo "*                                        Welcome to the GitOps playground by Cloudogu!                                            *"
  echo "***********************************************************************************************************************************"
  echo "*                                                                                                                                 *"
  echo "* The playground features three example applications (Sprint PetClinic - one for every gitops solution) in SCM-Manager. See here: *"
  echo "*                                                                                                                                 *"
  echo "* http://localhost:9091/scm/repo/fluxv1/petclinic-plain/code/sources/main/                                                        *"
  echo "* http://localhost:9091/scm/repo/fluxv2/petclinic-plain/code/sources/main/                                                        *"
  echo "* http://localhost:9091/scm/repo/argocd/petclinic-plain/code/sources/main/                                                        *"
  echo "*                                                                                                                                 *"
  echo "* Credentials for SCM-Manager and Jenkins are: scmadmin/scmadmin                                                                  *"
  echo "*                                                                                                                                 *"
  echo "* Right now, three Jenkins jobs are running: (when Jenkins is successfully deployed and running)                                  *"
  echo "*                                                                                                                                 *"
  echo "* http://localhost:9090/job/fluxv1-petclinic-plain/                                                                               *"
  echo "* http://localhost:9090/job/fluxv2-petclinic-plain/                                                                               *"
  echo "* http://localhost:9090/job/argocd-petclinic-plain/                                                                               *"
  echo "*                                                                                                                                 *"
  echo "* Some of these jobs may fail on startup due to concurrency issues. Just start the build process again manually.                  *"
  echo "* During the job, jenkins pushes into the corresponding GitOps repo and creates a pull request for production:                    *"
  echo "*                                                                                                                                 *"
  echo "* For Flux V1:                                                                                                                    *"
  echo "*                                                                                                                                 *"
  echo "* GitOps repo: http://localhost:9091/scm/repo/fluxv1/gitops/code/sources/main/                                                    *"
  echo "* Pull requests: http://localhost:9091/scm/repo/fluxv1/gitops/pull-requests                                                       *"
  echo "*                                                                                                                                 *"
  echo "* For Flux V2:                                                                                                                    *"
  echo "*                                                                                                                                 *"
  echo "* GitOps repo: http://localhost:9091/scm/repo/fluxv2/gitops/code/sources/main/                                                    *"
  echo "* Pull requests: http://localhost:9091/scm/repo/fluxv2/gitops/pull-requests                                                       *"
  echo "*                                                                                                                                 *"
  echo "* For ArgoCD:                                                                                                                     *"
  echo "*                                                                                                                                 *"
  echo "* GitOps repo: http://localhost:9091/scm/repo/argocd/gitops/code/sources/main/                                                    *"
  echo "* Pull requests: http://localhost:9091/scm/repo/argocd/gitops/pull-requests                                                       *"
  echo "*                                                                                                                                 *"
  echo "* After a successful Jenkins build, the application will be deployed into the cluster.                                            *"
  echo "* This may take a minute for the GitOps operator to sync.                                                                         *"
  echo "*                                                                                                                                 *"
  echo "* The staging application can be found at http://localhost:9093/ for Flux V1                                                      *"
  echo "* The staging application can be found at http://localhost:9095/ for Flux V2                                                      *"
  echo "* The staging application can be found at http://localhost:9097/ for ArgoCD                                                       *"
  echo "*                                                                                                                                 *"
  echo "* You can then go ahead and merge the pull request in order to deploy to production                                               *"
  echo "* After about 1 Minute, the GitOps operator deploys to production.                                                                *"
  echo "*                                                                                                                                 *"
  echo "* The production application can be found at http://localhost:9094/ for Flux V1                                                   *"
  echo "* The production application can be found at http://localhost:9096/ for Flux V2                                                   *"
  echo "* The production application can be found at http://localhost:9098/ for ArgoCD                                                    *"
  echo "***********************************************************************************************************************************"
}

function printUsage()
{
    echo "This script will install all necessary resources for Flux V1, Flux V2 and ArgoCD into your k8s-cluster."
    echo ""
    printParameters
    echo ""
}

function printParameters() {
    echo "The following parameters are valid"
    echo "-h | --help     >> Help screen"
    echo "-w | --welcome  >> Welcome screen"
    echo "-d | --debug    >> Debug output"
}

function confirm() {
  confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
  exit 0
}



COMMANDS=`getopt `





if [[ silent = "true" ]]; then
  confirm
  main "$@" > /dev/null 2>&1 & spinner "Installing GitOps Playground. This may take a minute or two or..."
elif [[ silent = "false" ]]; then
  confirm
  main "$@"
fi

printWelcomeScreen