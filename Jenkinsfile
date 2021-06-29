#!groovy
String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerRegistryPath() { 'cloudogu' }

def image
String imageName
String clusterName

@Library('github.com/cloudogu/ces-build-lib@1.47.1')
import com.cloudogu.ces.cesbuildlib.*

properties([
    // Keep only the last 10 build to preserve space
    disableConcurrentBuilds()
])

node('docker') {

    def git = new Git(this)

        timeout(activity: true, time: 30, unit: 'MINUTES') {

            catchError {
                try {
                    stage('Checkout') {
                        checkout scm
                        git.clean('')
                    }

                    stage('Build image') {
                        String imageTag = git.commitHashShort
                        imageName = "${dockerRegistryBaseUrl}/${dockerRegistryPath}/gop:${imageTag}"
                        String rfcDate = sh (returnStdout: true, script: 'date --rfc-3339 ns').trim()
                        image = docker.build(imageName, 
                                    "--build-arg BUILD_DATE='${rfcDate}' --build-arg VCS_REF='${git.commitHash}' .") // if using optional parameters you need to add the '.' argument at the end for docker to build the image
                    }

                    stage('Scan image and start gitops playground') {
                        parallel(
                            'scan image': {
                                scanImage(imageName)
                                saveScanResultsOnVulenrabilities()
                            },

                            'start gitops playground': {
                                String[] randomUUIDs = UUID.randomUUID().toString().split("-")
                                String uuid = randomUUIDs[randomUUIDs.length-1]
                                clusterName = "citest-" + uuid
                                startK3d(clusterName, imageName)

                                String ipV4 = setKubeConfigToK3dIp(clusterName)

                                docker.image(imageName) // contains the docker client binary
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
                                    image.push()
                                    image.push("latest")
                                } else {
                                    echo "Skipping deployment to github container registry because not a tag and not main branch."
                                }
                            }
                        }
                    }

                } finally {
                    if(clusterName){
                        sh "k3d cluster stop ${clusterName}"
                        sh "k3d cluster delete ${clusterName}"
                    }
                }
            }
            
            mailIfStatusChanged(git.commitAuthorEmail)
        }
}

def scanImage(imageName) {
    sh "mkdir -p .trivy/.cache"
    def trivyVersion = '0.18.0'
    def severityFlag = '--severity=CRITICAL'

    new Docker (this).image("aquasec/trivy:${trivyVersion}")
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

def startK3d(clusterName, imageName) {
    sh 'git config --global user.name "gop-ci-test"'
    sh 'git config --global user.email "gop-ci-test@test.com"'
    sh 'mkdir ./.kube'
    sh 'touch ./.kube/config'
    sh "yes | ./scripts/init-cluster.sh --cluster-name=${clusterName} --bind-localhost=false"

    sh "k3d image import -c ${clusterName} ${imageName}"
}


def setKubeConfigToK3dIp(clusterName) {
    String containerId = sh(
            script: "docker ps | grep ${clusterName}-server-0 | grep -o -m 1 '[^ ]*' | head -1",
            returnStdout: true
    ).trim()

    String ipV4 = sh(
            script: "docker inspect ${containerId} | grep -o  '\"IPAddress\": \"[0-9.\"]*' | grep -o '[0-9.*]*'",
            returnStdout: true
    ).trim()

    sh "sed -i -r 's/0.0.0.0([^0-9]+[0-9]*|\$)/${ipV4}:6443/g' ${env.WORKSPACE}/.kube/config"
    sh "cat ${env.WORKSPACE}/.kube/config"

    return ipV4
}
