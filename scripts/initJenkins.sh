#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"

PETCLINIC_COMMIT=949c5af
SPRING_BOOT_HELM_CHART_COMMIT=0.2.0
JENKINS_HELM_CHART_VERSION=3.1.9
SCMM_HELM_CHART_VERSION=2.13.0
SET_USERNAME="admin"
SET_PASSWORD="admin"

declare -A hostnames
hostnames[scmm]="localhost"
hostnames[jenkins]="localhost"
hostnames[argocd]="localhost"

declare -A ports
# get ports from values files
ports[scmm]=$(grep 'nodePort:' "${PLAYGROUND_DIR}"/scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')
ports[jenkins]=$(grep 'nodePort:' "${PLAYGROUND_DIR}"/jenkins/values.yaml | grep nodePort | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')
ports[argocd]=$(grep 'servicePortHttp:' "${PLAYGROUND_DIR}"/argocd/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')

REMOTE_CLUSTER=false

function initJenkins() {
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

}

function createSecrets() {
  createSecret scmm-credentials --from-literal=USERNAME=$SET_USERNAME --from-literal=PASSWORD=$SET_PASSWORD -n default
  createSecret jenkins-credentials --from-literal=jenkins-admin-user=$SET_USERNAME --from-literal=jenkins-admin-password=$SET_PASSWORD -n default
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

function authenticate() {
  # get jenkins crumb
  crumb=$(curl -s --cookie-jar /tmp/cookies -u admin:admin http://localhost:9090/crumbIssuer/api/json)

  # get jenkins api token
  token=$(curl -X POST -H "Jenkins-Crumb:$crumb" --cookie /tmp/cookies http://localhost:9090/me/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken\?newTokenName\=\foo -u admin:admin)

}

function createJob() {
  # createJob
  curl -s -XPOST 'http://localhost:9090/createItem?name=fooJob' -u admin:1135fdaf21646613c8601eb6a734ee922b --data-binary @/tmp/mylocalconfig.xml -H "Content-Type:text/xml"
}

function createCreds() {
  # create creds
  curl -X POST 'http://localhost:9090/credentials/store/system/domain/_/createCredentials' -u admin:1135fdaf21646613c8601eb6a734ee922b --data-urlencode 'json={
    "": "0",
    "credentials": {
      "scope": "GLOBAL",
      "id": "identification",
      "username": "manu",
      "password": "bar",
      "description": "linda",
      "$class": "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl"
    }
  }'
}

function installPlugin() {
  # install plugin
  curl -X POST -d '<jenkins><install plugin="disk-usage@0.28" /></jenkins>' --header 'Content-Type: text/xml' http://localhost:9090/pluginManager/installNecessaryPlugins -u admin:1135fdaf21646613c8601eb6a734ee922b

}

function restart() {
  # restart
  curl -X POST http://localhost:9090/safeRestart -u admin:1135fdaf21646613c8601eb6a734ee922b
}

authenticate

# initJenkins