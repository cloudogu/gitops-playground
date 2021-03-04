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

function authenticate() {
  # get jenkins crumb
  crumb=$(curl -s --cookie-jar /tmp/cookies -u $1:$2 http://$3:$4/crumbIssuer/api/json | jq -r '.crumb')

  # get jenkins api token
  token=$(curl -s -X POST -H "Jenkins-Crumb:$crumb" --cookie /tmp/cookies http://$3:$4/me/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken\?newTokenName\=\foo -u $1:$2 | jq -r '.data.tokenValue')
  echo $token
}

function createJob() {
  printf "Creating job '$3' ... "
  status=$(curl -s -X POST http://$1:$2/createItem?name=$3 -u $4:$token -H "Content-Type:text/xml" --data "$job_config" --write-out '%{http_code}')
  printStatus $status
}

function prepareScmManagerNamspaceJob() {
  job_config='<?xml version="1.1" encoding="UTF-8"?>
  <jenkins.branch.OrganizationFolder plugin="branch-api@2.6.2">
    <actions/>
    <description></description>
    <properties>
      <jenkins.branch.OrganizationChildHealthMetricsProperty>
        <templates>
          <com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric plugin="cloudbees-folder@6.15">
            <nonRecursive>false</nonRecursive>
          </com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>
        </templates>
      </jenkins.branch.OrganizationChildHealthMetricsProperty>
      <jenkins.branch.OrganizationChildOrphanedItemsProperty>
        <strategy class="jenkins.branch.OrganizationChildOrphanedItemsProperty$Inherit"/>
      </jenkins.branch.OrganizationChildOrphanedItemsProperty>
      <jenkins.branch.OrganizationChildTriggersProperty>
        <templates>
          <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin="cloudbees-folder@6.15">
            <spec>H H/4 * * *</spec>
            <interval>86400000</interval>
          </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
        </templates>
      </jenkins.branch.OrganizationChildTriggersProperty>
      <org.jenkinsci.plugins.docker.workflow.declarative.FolderConfig plugin="docker-workflow@1.25">
        <dockerLabel></dockerLabel>
        <registry plugin="docker-commons@1.17"/>
      </org.jenkinsci.plugins.docker.workflow.declarative.FolderConfig>
      <jenkins.branch.NoTriggerOrganizationFolderProperty>
        <branches>.*</branches>
      </jenkins.branch.NoTriggerOrganizationFolderProperty>
    </properties>
    <folderViews class="jenkins.branch.OrganizationFolderViewHolder">
      <owner reference="../.."/>
    </folderViews>
    <healthMetrics/>
    <icon class="jenkins.branch.MetadataActionFolderIcon">
      <owner class="jenkins.branch.OrganizationFolder" reference="../.."/>
    </icon>
    <orphanedItemStrategy class="com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy" plugin="cloudbees-folder@6.15">
      <pruneDeadBranches>true</pruneDeadBranches>
      <daysToKeep>-1</daysToKeep>
      <numToKeep>-1</numToKeep>
    </orphanedItemStrategy>
    <triggers>
      <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin="cloudbees-folder@6.15">
        <spec>H H/4 * * *</spec>
        <interval>86400000</interval>
      </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
    </triggers>
    <disabled>false</disabled>
    <navigators>
      <com.cloudogu.scmmanager.scm.ScmManagerNavigator plugin="scm-manager@1.7.1">
        <serverUrl>'$1'</serverUrl>
        <namespace>'$2'</namespace>
        <credentialsId>'$3'</credentialsId>
        <dependencyChecker class="com.cloudogu.scmmanager.scm.ScmManagerNavigator$$Lambda$267/0x0000000100a46c40"/>
        <traits>
          <com.cloudogu.scmmanager.scm.BranchDiscoveryTrait/>
          <com.cloudogu.scmmanager.scm.PullRequestDiscoveryTrait>
            <excludeBranchesWithPRs>false</excludeBranchesWithPRs>
          </com.cloudogu.scmmanager.scm.PullRequestDiscoveryTrait>
        </traits>
        <apiFactory>
          <credentialsLookup/>
        </apiFactory>
      </com.cloudogu.scmmanager.scm.ScmManagerNavigator>
    </navigators>
    <projectFactories>
      <org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectFactory plugin="workflow-multibranch@2.22">
        <scriptPath>Jenkinsfile</scriptPath>
      </org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectFactory>
    </projectFactories>
    <buildStrategies/>
    <strategy class="jenkins.branch.DefaultBranchPropertyStrategy">
      <properties class="empty-list"/>
    </strategy>
  </jenkins.branch.OrganizationFolder>'
  echo $job_config
}

function prepareMultiBranchPipelineJob() {
  job_config='<org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>
    <properties/>
    <folderViews class="jenkins.branch.MultiBranchProjectViewHolder">
      <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
    </folderViews>
    <healthMetrics>
      <com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>
      <nonRecursive>false</nonRecursive>
      </com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>
    </healthMetrics>
    <icon class="jenkins.branch.MetadataActionFolderIcon">
      <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
    </icon>
    <orphanedItemStrategy class="com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy">
      <pruneDeadBranches>false</pruneDeadBranches>
      <daysToKeep>-1</daysToKeep>
      <numToKeep>-1</numToKeep>
    </orphanedItemStrategy>
    <triggers/>
    <sources class="jenkins.branch.MultiBranchProject$BranchSourceList">
      <data>
        <jenkins.branch.BranchSource>
          <source class="jenkins.plugins.git.GitSCMSource">
            <id>'$1'</id>
            <remote>'$2'</remote>
            <credentialsId>'$3'</credentialsId>
            <includes>*</includes>
            <excludes/>
            <ignoreOnPushNotifications>false</ignoreOnPushNotifications>
          </source>
          <strategy class="jenkins.branch.DefaultBranchPropertyStrategy">
            <properties class="empty-list"/>
          </strategy>
        </jenkins.branch.BranchSource>
      </data>
      <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
    </sources>
    <factory class="org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory">
      <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
    </factory>
  </org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>'
  echo $job_config
}

function createCredentials() {
  printf "Creating credentials for $4 ..."
  status=$(curl -s -X POST http://$1:$2/credentials/store/system/domain/_/createCredentials -u $3:$token --data-urlencode 'json={
    "": "0",
    "credentials": {
      "scope": "GLOBAL",
      "id": "identification",
      "username": "'$4'",
      "password": "'$5'",
      "description": "'$6'",
      "$class": "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl"
    }
  }' --write-out '%{http_code}')
  printStatus $status
}

function installPlugin() {
  printf "Installing plugin $4 v$5 ... "
  status=$(curl -s -X POST http://$1:$2/pluginManager/installNecessaryPlugins -u $3:$token -d '<jenkins><install plugin="'$4'@'$5'"/></jenkins>' -H 'Content-Type: text/xml' --write-out '%{http_code}')
  printStatus $status
}

function safeRestart() {
  curl -X POST http://$1:$2/safeRestart -u $3:$token
}

function printStatus() {
  if [ $1 -eq 200 ] || [ $1 -eq 201 ] || [ $1 -eq 202 ] || [ $1 -eq 302 ]
  then
    echo -e '\u2705'
  else
    echo -e '\u274c'
  fi
}

token=$(authenticate ${SET_USERNAME} ${SET_PASSWORD} ${hostnames[jenkins]} ${ports[jenkins]})

#job_config=$(prepareScmManagerNamspaceJob "http://scmm-scm-manager/scm/" "fluxv1" "scmm-user")
#createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv1-petclinic-plain" ${SET_USERNAME}
#
#job_config=$(prepareScmManagerNamspaceJob "http://scmm-scm-manager/scm/" "fluxv1" "scmm-user")
#createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv1-petclinic-helm" ${SET_USERNAME}
#
#job_config=$(prepareScmManagerNamspaceJob "http://scmm-scm-manager/scm/" "fluxv1" "scmm-user")
#createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv1-nginx" ${SET_USERNAME}
#
#job_config=$(prepareScmManagerNamspaceJob "http://scmm-scm-manager/scm/" "fluxv2" "scmm-user")
#createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv2-petclinic-plain" ${SET_USERNAME}
#
#job_config=$(prepareScmManagerNamspaceJob "http://scmm-scm-manager/scm/" "argocd" "scmm-user")
#createJob ${hostnames[jenkins]} ${ports[jenkins]} "argocd-petclinic-plain" ${SET_USERNAME}



job_config=$(prepareMultiBranchPipelineJob "fluxv1-nginx" "http://scmm-scm-manager/scm/repo/fluxv1/nginx-helm" "scmm-user")
createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv1-nginx" ${SET_USERNAME}

job_config=$(prepareMultiBranchPipelineJob "fluxv1-petclinic-helm" "http://scmm-scm-manager/scm/repo/fluxv1/petclinic-helm" "scmm-user")
createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv1-petclinic-helm" ${SET_USERNAME}

job_config=$(prepareMultiBranchPipelineJob "fluxv1-petclinic-plain" "http://scmm-scm-manager/scm/repo/fluxv1/petclinic-plain" "scmm-user")
createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv1-petclinic-plain" ${SET_USERNAME}

job_config=$(prepareMultiBranchPipelineJob "fluxv2-petclinic-plain" "http://scmm-scm-manager/scm/repo/fluxv2/petclinic-plain" "scmm-user")
createJob ${hostnames[jenkins]} ${ports[jenkins]} "fluxv2-petclinic-plain" ${SET_USERNAME}

job_config=$(prepareMultiBranchPipelineJob "argocd-petclinic-plain" "http://scmm-scm-manager/scm/repo/argocd/petclinic-plain" "scmm-user")
createJob ${hostnames[jenkins]} ${ports[jenkins]} "argocd-petclinic-plain" ${SET_USERNAME}

createCredentials ${hostnames[jenkins]} ${ports[jenkins]} ${SET_USERNAME} "someName" "somePW" "someDescription"

installPlugin ${hostnames[jenkins]} ${ports[jenkins]} ${SET_USERNAME} "disk-usage" "0.28"

#safeRestart ${hostnames[jenkins]} ${ports[jenkins]} ${SET_USERNAME}

#initJenkins