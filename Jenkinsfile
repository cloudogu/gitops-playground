#!groovy
@Library('github.com/cloudogu/ces-build-lib@1.47.1')
import com.cloudogu.ces.cesbuildlib.*

String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerImageName() { 'cloudogu/gitops-playground' }
String getTrivyVersion() { '0.18.3' }

properties([
    // Dont keep builds forever to preserve space
    buildDiscarder(logRotator(numToKeepStr: '50')),

    // For now allow concurrent builds.
    // This is a slight risk of failing builds if two Jobs of the same branch install k3d (workspace-local) at the same time.
    // If this happens to occurr often, add the following here: disableConcurrentBuilds(),
])

node('docker') {

    def git = new Git(this)

    timestamps {
    catchError {
    timeout(activity: true, time: 30, unit: 'MINUTES') {

        stage('Checkout') {
            checkout scm
            git.clean('')
        }

        stage('Build image') {
            String imageTag = git.commitHashShort
            imageName = "${dockerRegistryBaseUrl}/${dockerImageName}:${imageTag}"
            String rfcDate = sh(returnStdout: true, script: 'date --rfc-3339 ns').trim()
            image = docker.build(imageName,
                    "--build-arg BUILD_DATE='${rfcDate}' " +
                    "--build-arg VCS_REF='${git.commitHash}' " +
                    // if using optional parameters you need to add the '.' argument at the end for docker to build the image
                    ".")
        }

        parallel(
                'Scan image': {
                    stage('Scan image') {
                        scanImage(imageName)
                        saveScanResultsOnVulenrabilities()
                    }
                },

                'Start gitops playground': {
                    stage('start gitops playground') {
                        clusterName = createClusterName()
                        startK3d(clusterName)

                        String ipV4 = setKubeConfigToK3dIp(clusterName)

                        docker.image(imageName)
                                .inside("-e KUBECONFIG=${env.WORKSPACE}/.kube/config " +
                                        " --network=k3d-${clusterName} --entrypoint=''" ) {
                                    
                                    sh "./scripts/apply.sh --yes --debug --trace --argocd --cluster-bind-address=${ipV4}"
                                }
                    }
                }
        )

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
    }}

    stage('Stop k3d') {
        if (clusterName) {
            // Don't fail build if cleaning up fails
            sh "k3d cluster delete ${clusterName} || true"
        }
    }

    mailIfStatusChanged(git.commitAuthorEmail)
}}

def scanImage(imageName) {
    trivy('.trivy/trivyOutput-unfixable.txt', '--severity=CRITICAL --ignore-unfixed', imageName)
    trivy('.trivy/trivyOutput-all.txt', '', imageName)
}

private void trivy(output, flags, imageName) {
    sh 'mkdir -p .trivy/.cache'
    new Docker(this).image("aquasec/trivy:${trivyVersion}")
            .mountJenkinsUser()
            .mountDockerSocket()
            .inside("-v ${env.WORKSPACE}/.trivy/.cache:/root/.cache/") {
                sh "trivy image -o ${output} ${flags} ${imageName}"
            }
}

def saveScanResultsOnVulenrabilities() {
    if (readFile('.trivy/trivyOutput-all.txt').size() != 0) {
        archiveArtifacts artifacts: '.trivy/trivyOutput-all.txt'
    }
    if (readFile('.trivy/trivyOutput-unfixable.txt').size() != 0) {
        archiveArtifacts artifacts: '.trivy/trivyOutput-unfixable.txt'
        unstable('There are critical and fixable vulnerabilities.')
    }
}

def startK3d(clusterName) {
    sh "mkdir -p ${WORKSPACE}/.kd3/bin"
    
    withEnv(["HOME=${WORKSPACE}", "PATH=${WORKSPACE}/.kd3/bin:${PATH}"]) { // Make k3d write kubeconfig to WORKSPACE
        // Install k3d binary to workspace in order to avoid concurrency issues
        sh "if ! command -v k3d >/dev/null 2>&1; then " +
                "curl -s https://raw.githubusercontent.com/rancher/k3d/main/install.sh |" +
                  'TAG=v$(sed -n "s/^K3D_VERSION=//p" scripts/init-cluster.sh)' +
                  "K3D_INSTALL_DIR=${WORKSPACE}/.kd3/bin" +
                     'bash -s -- --no-sudo; fi'
        sh "yes | ./scripts/init-cluster.sh --cluster-name=${clusterName} --bind-localhost=false"
    }
}

def setKubeConfigToK3dIp(clusterName) {
    // As we're will run the playground from another container, we need to use the IP of the k3d container in kubeconfig

    String ipV4 = sh(
            script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${clusterName}-server-0",
            returnStdout: true
    ).trim()

    sh "sed -i -r 's/0.0.0.0([^0-9]+[0-9]*|\$)/${ipV4}:6443/g' ${env.WORKSPACE}/.kube/config"

    return ipV4
}

String createClusterName() {
    String[] randomUUIDs = UUID.randomUUID().toString().split("-")
    String uuid = randomUUIDs[randomUUIDs.length-1]
    return "citest-" + uuid
}

def image
String imageName
String clusterName