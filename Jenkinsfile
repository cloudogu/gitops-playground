#!groovy
String getScmManagerCredentials() { 'cesmarvin-github' }
String getCesBuildLibRepo() { "https://github.com/cloudogu/ces-build-lib/" }
String getCesBuildLibVersion() { '1.46.1' }
String getK8sPlaygroundRepo() {"https://github.com/cloudogu/k8s-gitops-playground/"}
String getMainBranch() { 'feature/add_build_pipeline' }
String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerRegistryPath() { 'cloudogu' }

cesBuildLib = library(identifier: "ces-build-lib@${cesBuildLibVersion}",
        retriever: modernSCM([$class: 'GitSCMSource', remote: cesBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.ces.cesbuildlib

properties([
        // Keep only the last 10 build to preserve space
        disableConcurrentBuilds()
])

node('docker') {
    properties([
            parameters([
                    booleanParam(
                            defaultValue: false,
                            description: 'Runs this pipeline as if it was pushed to main. This includes building and scanning the gop image and running the playground.',
                            name: 'Run as main'
                    )
            ])
    ])

    if( "${env.BRANCH_NAME}" == 'main' || params.test) {
    def git = cesBuildLib.Git.new(this, scmManagerCredentials)

        timeout(activity: true, time: 30, unit: 'MINUTES') {

            catchError {
                try {
                    stage('Checkout') {
                        git url: k8sPlaygroundRepo, branch: mainBranch, changelog: false, poll: false
                        git.clean('')
                    }

                    stage('Build image') {
                        String imageTag = "latest"
                        imageName = "${dockerRegistryBaseUrl}/${dockerRegistryPath}/gop:${imageTag}"
                        def docker = cesBuildLib.Docker.new(this)
                        String rfcDate = sh (returnStdout: true, script: 'date --rfc-3339 ns').trim()
                        image = docker.build(imag   
                                "--build-arg BUILD_DATE='${rfcDate}' --build-arg VCS_REF='${git.commitHash}'")
                    }

                    stage('Scan image and start gop') {
                        parallel(
                            'scan image': {
                                scanImage()
                                saveScanResultsOnVulenrabilities()
                            },

                            'start gop': {
                                String[] randomUUIDs = UUID.randomUUID().toString().split("-")
                                String uuid = randomUUIDs[randomUUIDs.length-1]
                                clusterName = "citest-" + uuid

                                startK3d(clusterName)

                                setKubeConfigToK3dIp(clusterName)

                                cesBuildLib.Docker.new(this).image(imageName) // contains the docker client binary
                                    .inside("--entrypoint='' -e KUBECONFIG=${this.env.WORKSPACE}/.kube/config ${this.pwd().equals(this.env.WORKSPACE) ? '' : "-v ${this.env.WORKSPACE}:${this.env.WORKSPACE}"} --network=k3d-${clusterName}") {
                                        sh "yes | ./scripts/apply.sh --debug -x --argocd --cluster-bind-address=${ipV4}"
                                }
                            }
                        )
                    }
                    stage('Push image') {
                        if (isBuildSuccessful()) {
                            docker.withRegistry("https://${dockerRegistryBaseUrl}", 'cesmarvin-github') {
                                if (git.isTag()) {
                                    image.push(git.tag)
                                } else if (env.BRANCH_NAME == 'main') {
                                    image.push("latest")
                                } else {
                                    echo "Skipping deployment to docker hub because current branch is ${env.BRANCH_NAME}."
                                }
                            }
                        }
                    }

                } finally {
                    sh "k3d cluster stop ${clusterName}"
                    sh "k3d cluster delete ${clusterName}"
                }
            }
        }
    }
}

def scanImage() {
    sh "mkdir -p .trivy/.cache"
    def docker = cesBuildLib.Docker.new(this)
    def trivyVersion = '0.18.0'
    def severityFlag = '--severity=CRITICAL'

    docker.image("aquasec/trivy:${trivyVersion}")
            .mountJenkinsUser()
            .mountDockerSocket()
            .inside("-v ${env.WORKSPACE}/.trivy/.cache:/root/.cache/") {
                sh "trivy image -o .trivy/trivyOutput.txt ${severityFlag} ${imageName}"
            }
}

def saveScanResultsOnVulenrabilities() {
    if (readFile(".trivy/trivyOutput.txt").size() != 0) {
        currentBuild.result = 'ABORTED'
        error('There are critical and fixable vulnerabilities.')
        archiveArtifacts artifacts: ".trivy/trivyOutput.txt"
    }
}

def startK3d(clusterName) {
    sh 'git config --global user.name "gop-ci-test"'
    sh 'git config --global user.email "gop-ci-test@test.com"'
    sh 'mkdir ./.kube'
    sh 'touch ./.kube/config'
    sh "yes | ./scripts/init-cluster.sh --cluster-name=${clusterName}"

    sh "k3d image import -c ${clusterName} ${imageName}"
}

def setKubeConfigToK3dIp(clusterName) {
    String containerId = sh(
            script: "docker ps | grep ${clusterName}-server-0 | grep -o -m 1 '[^ ]*' | head -1",
            returnStdout: true
    ).trim()

    ipV4 = sh(
            script: "docker inspect ${containerId} | grep -o  '\"IPAddress\": \"[0-9.\"]*' | grep -o '[0-9.*]*'",
            returnStdout: true
    ).trim()

    sh "sed -i -r 's/0.0.0.0([^0-9]+[0-9]*|\$)/${ipV4}:6443/g' ${env.WORKSPACE}/.kube/config"
    sh "cat ${env.WORKSPACE}/.kube/config"
}

def cesBuildLib
def image
String imageName
String clusterName
String ipV4