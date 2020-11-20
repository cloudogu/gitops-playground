#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

SCM_USER=scmadmin
SCM_PWD=scmadmin

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"
JENKINS_HOME="/tmp/k8s-gitops-playground-jenkins-agent"

PETCLINIC_COMMIT=949c5af
# get scm-manager port from values
SCMM_PORT=$(grep -A1 'service:' "${PLAYGROUND_DIR}"/scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')

PETCLINIC_COMMIT=949c5af
# get scm-manager port from values
SCMM_PORT=$(grep -A1 'service:' scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')

source ${ABSOLUTE_BASEDIR}/utils.sh

function main() {
  confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
    exit 0

  applyBasicK8sResources

  initFluxV1

  initFluxV2

  initArgo

  # Start Jenkins last, so all repos have been initialized when repo indexing starts
  initJenkins

  # Create Jenkins agent working dir explicitly. Otherwise it seems to be owned by root
  mkdir -p ${JENKINS_HOME}

  printWelcomeScreen
}

function applyBasicK8sResources() {
  kubectl apply -f k8s-namespaces

  kubectl apply -f jenkins/resources
  kubectl apply -f scm-manager/resources

  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-components.yaml

  helm repo add jenkins https://charts.jenkins.io
  helm repo add fluxcd https://charts.fluxcd.io
  helm repo add helm-stable https://charts.helm.sh/stable
  helm repo add argo https://argoproj.github.io/argo-helm
  helm repo add bitnami https://charts.bitnami.com/bitnami

  helm upgrade -i scmm --values scm-manager/values.yaml \
    --set-file=postStartHookScript=scm-manager/initscmm.sh \
    scm-manager/chart -n default

  helm upgrade -i docker-registry --values docker-registry/values.yaml --version 1.9.4 helm-stable/docker-registry -n default
  
  pushHelmChartRepo 'application/spring-boot-helm-chart'
}

function initJenkins() {
  # Make sure to run Jenkins and Agent containers as the current user. Avoids permission problems.
  # Find out the docker group and put the agent into it. Otherwise it has no permission to access  the docker host.
  helm upgrade -i jenkins --values jenkins/values.yaml \
    --set master.runAsUser=$(id -u) \
    --set agent.runAsUser=$(id -u) \
    --set agent.runAsGroup=$(getent group docker | awk -F: '{ print $3}') \
    --version 2.13.0 jenkins/jenkins -n default
}

function initFluxV1() {
  pushPetClinicRepo 'petclinic/fluxv1/plain-k8s' 'application/petclinic-plain'
  initRepo 'cluster/gitops'
  initRepoWithSource 'application/nginx' 'nginx/fluxv1'

  helm upgrade -i flux-operator --values flux-operator/values.yaml --version 1.3.0 fluxcd/flux -n default
  helm upgrade -i helm-operator --values helm-operator/values.yaml --version 1.0.2 fluxcd/helm-operator -n default
}

function initFluxV2() {
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml
  kubectl apply -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-kustomization.yaml

  pushPetClinicRepo 'petclinic/fluxv2/plain-k8s' 'fluxv2/petclinic-plain'
  initRepoWithSource 'fluxv2/gitops' 'fluxv2'
}

function initArgo() {
  helm upgrade -i argocd --values argocd/values.yaml --version 2.9.5 argo/argo-cd -n default
  kubectl apply -f argocd/resources

  # set argocd admin password to 'admin' here, because it does not work through the helm chart
  kubectl patch secret -n default argocd-secret -p '{"stringData": { "admin.password": "$2y$10$GsLZ7KlAhW9xNsb10YO3/O6jlJKEAU2oUrBKtlF/g1wVlHDJYyVom"}}'

  pushPetClinicRepo 'petclinic/argocd/plain-k8s' 'argocd/petclinic-plain'
  initRepo 'argocd/gitops'
  initRepoWithSource 'argocd/nginx-helm' 'nginx/argocd'
}

function pushPetClinicRepo() {
  LOCAL_PETCLINIC_SOURCE="$1"
  TARGET_REPO_SCMM="$2"

  TMP_REPO=$(mktemp -d)

  git clone -n https://github.com/cloudogu/spring-petclinic.git "${TMP_REPO}" --quiet
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
  echo -n "Waiting for SCM-Manager to become available at http://localhost:${SCMM_PORT}/scm"
  while [[ "$(curl -s -L -o /dev/null -w ''%{http_code}'' "http://localhost:${SCMM_PORT}/scm")" -ne "200" ]]; do
    echo -n .
    sleep 2
  done
  echo
}

function initRepo() {
  echo "initiating repo with $1"
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    git checkout main --quiet || git checkout -b main --quiet
    echo "# gitops" >README.md
    git add README.md
    # exits with 1 if there were differences and 0 means no differences.
    if ! git diff-index --exit-code --quiet HEAD --; then
      git commit -m "Add readme" --quiet
    fi
    waitForScmManager
    git push -u "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  setMainBranch "${TARGET_REPO_SCMM}"
}

function initRepoWithSource() {
  echo "initiating repo $1 with source $2"
  TARGET_REPO_SCMM="$1"
  SOURCE_REPO="$2"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
  (
    cd "${TMP_REPO}"
    cp -r "${PLAYGROUND_DIR}/${SOURCE_REPO}"/* .
    git checkout main --quiet || git checkout -b main --quiet
    git add .
    git commit -m "Init ${TARGET_REPO_SCMM}" --quiet || true
    waitForScmManager
    git push -u "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force
  )

  rm -rf "${TMP_REPO}"

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
  echo "Welcome to Cloudogu's GitOps playground!"
  echo
  echo "The playground features an example application (Spring PetClinic) in SCM-Manager. See here: "
  echo "http://localhost:9091/scm/repo/application/petclinic-plain/code/sources/main/"
  echo "Credentials for SCM-Manager and Jenkins are: scmadmin/scmadmin"
  echo
  echo "A simple deployment can be triggered by changing the message.properties, for example:"
  echo "http://localhost:9091/scm/repo/application/petclinic-plain/code/sources/main/src/main/resources/messages/messages.properties/"
  echo
  echo "After saving, this those Jenkins jobs are triggered:"
  echo "http://localhost:9090/job/petclinic-plain/job/master"
  echo "http://localhost:9090/job/nginx/job/main"
  echo
  echo "During the job, jenkins pushes into GitOps repo and creates a pull request for production:"
  echo "GitOps repo: http://localhost:9091/scm/repo/cluster/gitops/code/sources/main/"
  echo "Pull requests: http://localhost:9091/scm/repo/cluster/gitops/pull-requests"
  echo
  echo "After about 1 Minute, the GitOps operator Flux deploys to staging."
  echo "The petclinic staging application can be found at http://localhost:9093/"
  echo "While nginx staging can be found at http://localhost:9095"
  echo
  echo "You can then go ahead and merge the pull request in order to deploy to production"
  echo "After about 1 Minute, the GitOps operator Flux deploys to production."
  echo "The petclinic prod application can be found at http://localhost:9094/"
  echo "While nginx prod can be found at http://localhost:9096"
}

main "$@"
