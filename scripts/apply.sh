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

source ${ABSOLUTE_BASEDIR}/utils.sh

function main() {
  confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
    exit 0

  applyK8sResources

  pushPetClinicRepo 'petclinic/fluxv1/plain-k8s' 'application/petclinic-plain'
  
  initRepo 'cluster/gitops'

  printWelcomeScreen
}

function applyK8sResources() {
  kubectl apply -f k8s-namespaces/staging.yaml
  kubectl apply -f k8s-namespaces/production.yaml

  kubectl apply -f jenkins/resources
  kubectl apply -f scm-manager/resources

  helm repo add jenkins https://charts.jenkins.io
  helm repo add fluxcd https://charts.fluxcd.io
  helm repo add helm-stable https://charts.helm.sh/stable

  helm upgrade -i scmm --values scm-manager/values.yaml --set-file=postStartHookScript=scm-manager/initscmm.sh scm-manager/chart -n default
  helm upgrade -i jenkins --values jenkins/values.yaml --version 2.13.0 jenkins/jenkins -n default
  helm upgrade -i flux-operator --values flux-operator/values.yaml --version 1.3.0 fluxcd/flux -n default
  helm upgrade -i helm-operator --values helm-operator/values.yaml --version 1.0.2 fluxcd/helm-operator -n default
  helm upgrade -i docker-registry --values docker-registry/values.yaml --version 1.9.4 helm-stable/docker-registry -n default
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
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)

  git clone "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    git checkout -b main  --quiet
    echo "# gitops" > README.md
    git add README.md
    git commit -m "Add readme" --quiet
    waitForScmManager
    git push -u "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
  )
}

function printWelcomeScreen() {
  echo "Welcome to Cloudogu's GitOps playground!"
  echo
  echo "The playground features an example application (Sprint PetClinic) in SCM-Manager. See here: "
  echo "http://localhost:9091/scm/repo/application/petclinic-plain/code/sources/master/"
  echo "Credentials for SCM-Manager and Jenkins are: scmadmin/scmadmin"
  echo
  echo "A simple deployment can be triggered by changing the message.properties, for example:"
  echo "http://localhost:9091/scm/repo/application/petclinic-plain/code/sources/master/src/main/resources/messages/messages.properties/"
  echo
  echo "After saving, this Jenkins job is triggered:"
  echo "http://localhost:9090/job/petclinic-plain/job/master"
  echo "During the job, jenkins pushes into GitOps repo and creates a pull request for production:"
  echo "GitOps repo: http://localhost:9091/scm/repo/cluster/gitops/code/sources/master/"
  echo "Pull requests: http://localhost:9091/scm/repo/cluster/gitops/pull-requests"
  echo
  echo "After about 1 Minute, the GitOps operator Flux deploys to staging."
  echo "The staging application can be found at http://localhost:9093/"
  echo
  echo "You can then go ahead and merge the pull request in order to deploy to production"
  echo "After about 1 Minute, the GitOps operator Flux deploys to production."
  echo "The prod application can be found at http://localhost:9094/"
}

main "$@"
