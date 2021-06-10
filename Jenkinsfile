#!groovy
String getScmManagerCredentials() { 'scmm-user' }
String getCesBuildLibRepo() { "${env.SCMM_URL}/repo/common/ces-build-lib/" }
String getK8sPlaygroundRepo() {"${env.SCMM_URL}/repo/common/k8s-gitops-playground/"}
String getCesBuildLibVersion() { '1.46.1' }
String getMainBranch() { 'feature/replace_k3s_with_k3d' }
String getBaseImage() { "${env.REGISTRY_URL}/alpine-bash-curl-git:0.0.1" }

cesBuildLib = library(identifier: "ces-build-lib@${cesBuildLibVersion}",
        retriever: modernSCM([$class: 'GitSCMSource', remote: cesBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.ces.cesbuildlib

properties([
        // Keep only the last 10 build to preserve space
        disableConcurrentBuilds()
])

node('docker') {

    def git = cesBuildLib.Git.new(this, scmManagerCredentials)

    timeout(activity: true, time: 30, unit: 'MINUTES') {

        catchError {

            stage('Checkout') {
                git url: k8sPlaygroundRepo, branch: mainBranch, changelog: false, poll: false
                git.clean('')
            }

            stage('init gop') {
                cesBuildLib.Docker.new(this).image(baseImage) // contains the docker client binary
                        .inside("${this.pwd().equals(this.env.WORKSPACE) ? '' : "-v ${this.env.WORKSPACE}:${this.env.WORKSPACE} "}" +
                                '--entrypoint="" -e SETUP_LOCAL_GOP=false -u 0:133 -v /tmp/docker/:/tmp/docker/ -v /var/run/docker.sock:/var/run/docker.sock -e PATH=/usr/local/openjdk-8/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/tmp/docker') {
                            sh 'git config --global user.name "gop-ci-test"'
                            sh 'git config --global user.email "gop-ci-test@test.com"'
                            CLUSTER_NAME = "citest"
                            sh "yes | ./scripts/init-cluster.sh --cluster-name=${CLUSTER_NAME}"
                            sh "kubectl config use-context k3d-${CLUSTER_NAME}"

                            DOCKER_NETWORK = sh (
                                    script: "docker network ls | grep -o \"[a-zA-Z0-9-]*${CLUSTER_NAME}\"",
                                    returnStdout: true
                            ).trim()

                            HOSTNAME = sh (
                                    script: "cat /etc/hostname",
                                    returnStdout: true
                            ).trim()

                            sh "docker network connect ${DOCKER_NETWORK} ${HOSTNAME}"
                            sh "echo ${HOSTNAME}"

                            CONTAINER_ID = sh (
                                    script: "docker ps | grep ${CLUSTER_NAME}-server-0 | grep -o -m 1 '[^ ]*' | head -1",
                                    returnStdout: true
                            ).trim()

                            IP_V4 = sh (
                                    script: "docker inspect ${CONTAINER_ID} | grep -o  '\"IPAddress\": \"[0-9.\"]*' | grep -o '[0-9.*]*'",
                                    returnStdout: true
                            ).trim()


                            sh "sed -i -r 's/0.0.0.0([^0-9]+[0-9]*|\$)/${IP_V4}:6443/g' ~/.kube/config"
                            sh "cat ~/.kube/config"
                            sh "kubectl cluster-info"
                            sh "yes | ./scripts/apply.sh --debug --argocd --cluster-bind-address=${IP_V4}"
                            sh "k3d cluster stop ${CLUSTER_NAME}"
                            sh "k3d cluster delete ${CLUSTER_NAME}"
                        }
            }
        }
    }
}

def cesBuildLib
