#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

if [[ -z ${PLAYGROUND_DIR+x} ]]; then
  BASEDIR=$(dirname $0)
  ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
  PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && cd .. && pwd)"
fi

PETCLINIC_COMMIT=949c5af
SPRING_BOOT_HELM_CHART_COMMIT=0.2.0
JENKINS_HELM_CHART_VERSION=3.1.9
SCMM_HELM_CHART_VERSION=2.13.0
SET_USERNAME="admin"
SET_PASSWORD="admin"

REMOTE_CLUSTER=false

source ${PLAYGROUND_DIR}/scripts/jenkins/jenkins-REST-client.sh

function initializeLocalJenkins() {
    # Mark the first node for Jenkins and agents. See jenkins/values.yamls "agent.workingDir" for details.
  # Remove first (in case new nodes were added)
  kubectl label --all nodes node- >/dev/null
  kubectl label $(kubectl get node -o name | sort | head -n 1) node=jenkins

  kubectl apply -f k8s-namespaces || true

  createSecrets

  kubectl apply -f jenkins/resources || true

  helm repo add jenkins https://charts.jenkins.io
  helm repo update

  # Find out the docker group and put the agent into it. Otherwise it has no permission to access  the docker host.
  helm upgrade -i jenkins --values jenkins/values.yaml \
    $(jenkinsHelmSettingsForLocalCluster) --set agent.runAsGroup=$(queryDockerGroupOfJenkinsNode) \
    --version ${JENKINS_HELM_CHART_VERSION} jenkins/jenkins -n default

  JENKINS_URL=${1}
  JENKINS_USERNAME=${2}
  JENKINS_PASSWORD=${3}
  SCMM_URL="${4}"
  SCMM_PASSWORD="${5}"

  initialize
}

function createSecrets() {
  createSecret scmm-credentials --from-literal=USERNAME=$SET_USERNAME --from-literal=PASSWORD=$SET_PASSWORD -n default
  createSecret jenkins-credentials --from-literal=jenkins-admin-user=$SET_USERNAME --from-literal=jenkins-admin-password=$SET_PASSWORD -n default
  createSecret gitops-scmm --from-literal=USERNAME=gitops --from-literal=PASSWORD=$SET_PASSWORD -n default
}

function createSecret() {
  kubectl create secret generic "$@" --dry-run=client -oyaml | kubectl apply -f-
}

function jenkinsHelmSettingsForLocalCluster() {
  if [[ $REMOTE_CLUSTER != true ]]; then
    # Run Jenkins and Agent pods as the current user.
    # Avoids file permission problems when accessing files on the host that were written from the pods

    # We also need a host port, so jenkins can be reached via localhost:9090
    # But: This helm charts only uses the nodePort value, if the type is "NodePort". So change it for local cluster.
    echo "--set controller.runAsUser=$(id -u) --set agent.runAsUser=$(id -u)" \
      "--set controller.serviceType=NodePort"
  fi
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

function waitForJenkins() {
  echo -n "Waiting for Jenkins to become available at ${JENKINS_URL}/login"
  while [[ $(curl -s -L -o /dev/null -w ''%{http_code}'' "${JENKINS_URL}/login") -ne "200" ]]; do
    echo -n .
    sleep 2
  done
  echo ""
}

function initializeRemoteJenkins() {
  JENKINS_URL=${1}
  JENKINS_USERNAME=${2}
  JENKINS_PASSWORD=${3}
  SCMM_URL="${4}"
  SCMM_PASSWORD="${5}"

  initialize
}

function initialize() {
  waitForJenkins

  token=$(authenticate)

  installPlugin "subversion" "2.14.0"
  installPlugin "docker-workflow" "1.25"
  installPlugin "docker-plugin" "1.2.1"
  installPlugin "job-dsl" "1.77"
  installPlugin "pipeline-utility-steps" "2.6.1"
  installPlugin "junit" "1.48"
  installPlugin "scm-manager" "1.5.1"
  installPlugin "html5-notifier-plugin" "1.5"

  safeRestart
  waitForJenkins

  createCredentials "scmm-user" "gitops" "${SCMM_PASSWORD}" "some credentials for accessing scm-manager"

  createJob "fluxv1-applications" "${SCMM_URL}" "fluxv1" "scmm-user"
  createJob "fluxv2-applications" "${SCMM_URL}" "fluxv2" "scmm-user"
  createJob "argocd-applications" "${SCMM_URL}" "argocd" "scmm-user"
}