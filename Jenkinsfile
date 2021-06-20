#!groovy
String getScmManagerCredentials() { 'cesmarvin-github' }
String getCesBuildLibRepo() { "https://github.com/cloudogu/ces-build-lib/" }
String getCesBuildLibVersion() { '1.46.1' }
String getK8sPlaygroundRepo() {"https://github.com/cloudogu/k8s-gitops-playground/"}
String getMainBranch() { 'feature/add_build_pipeline' }
String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerRegistryPath() { 'cloudogu' }

//- oss.cloudogu.com Jenkins (mit GitHub anmelden!)
//- Build nur auf main ausführen und wenn PR oder wenn per parameter explizit gesetzt
//- automatischer Image Build
//- automatische Vuln Scans mit trivy in ces-build-lib
//- Image pushen nur auf main und mit git.commitHashShort (siehe ces-build-lib) als version + latest -> ghcr
//    - Releases entweder latest + git commit als docker tag oder git tag als docker tag. Bsp

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
                            description: 'Run Test',
                            name: 'test'
                    )
            ])
    ])

    if( "${env.BRANCH_NAME}" == 'main' || params.test) {
    def git = cesBuildLib.Git.new(this, scmManagerCredentials)

        timeout(activity: true, time: 30, unit: 'MINUTES') {

            catchError {

                stage('Checkout') {
                    git url: k8sPlaygroundRepo, branch: mainBranch, changelog: false, poll: false
                    git.clean('')
                }

                def image
                def imageName
                stage('Build image') {
                    docker.withRegistry("https://${dockerRegistryBaseUrl}", 'cesmarvin-github') {
                        String imageTag = "1.0.0"
                        imageName = "${dockerRegistryBaseUrl}/${dockerRegistryPath}/gop:${imageTag}"
                        def docker = cesBuildLib.Docker.new(this)
                        image = docker.build(imageName)
    //                    image.push()
                    }
    //              if (isBuildSuccessful()) {
    //              } else {
    //                  echo 'Skipping docker push, because build not successful'
    //              }
                }


                stage('Scan') {
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

                    def vulnerabilitesFile = readFile(".trivy/trivyOutput.txt")
                    
                    if (vulnerabilitesFile.size() != 0) {
                        currentBuild.result = 'ABORTED'
                        error('There are critical and fixable vulnerabilities.')
                        archiveArtifacts artifacts: ".trivy/trivyOutput.txt"
                    }
                }

                stage('Test') {
                    sh 'git config --global user.name "gop-ci-test"'
                    sh 'git config --global user.email "gop-ci-test@test.com"'
                    CLUSTER_NAME = "citest"
                    sh "yes | ./scripts/init-cluster.sh --cluster-name=${CLUSTER_NAME}"

                    DOCKER_NETWORK = sh(
                            script: "docker network ls | grep -o \"[a-zA-Z0-9-]*${CLUSTER_NAME}\"",
                            returnStdout: true
                    ).trim()

                    HOSTNAME = sh(
                            script: "cat /etc/hostname",
                            returnStdout: true
                    ).trim()

                    sh "docker network connect ${DOCKER_NETWORK} ${HOSTNAME}"
                    sh "echo ${HOSTNAME}"

                    CONTAINER_ID = sh(
                            script: "docker ps | grep ${CLUSTER_NAME}-server-0 | grep -o -m 1 '[^ ]*' | head -1",
                            returnStdout: true
                    ).trim()

                    IP_V4 = sh(
                            script: "docker inspect ${CONTAINER_ID} | grep -o  '\"IPAddress\": \"[0-9.\"]*' | grep -o '[0-9.*]*'",
                            returnStdout: true
                    ).trim()

                    sh "k3d image import -c ${CLUSTER_NAME} ${imageName}"

                    sh "sed -i -r 's/0.0.0.0([^0-9]+[0-9]*|\$)/${IP_V4}:6443/g' ~/.kube/config"
                    sh "cat ~/.kube/config"

                    cesBuildLib.Docker.new(this).image(imageName) // contains the docker client binary
                        .inside("${this.pwd().equals(this.env.WORKSPACE) ? '' : "-v ${this.env.WORKSPACE}:${this.env.WORKSPACE} "}" +
                        '--entrypoint="" -e SETUP_LOCAL_GOP=false -v "~/.kube/config:~/.kube/config"') {

                    sh "kubectl config use-context k3d-${CLUSTER_NAME}"
                    sh "kubectl cluster-info"
//                                sh "kubectl create serviceaccount gop-job-executer -n default"
//                                sh "kubectl create clusterrolebinding gop-job-executer --clusterrole=cluster-admin --serviceaccount=default:gop-job-executer"
//                                sh "kubectl run gop --rm -i --tty --image-pull-policy='Never' --image ${imageName} --serviceaccount gop-job-executer -- --argocd --fluxv1"
                    sh "yes | ./scripts/apply.sh --debug -x --argocd --cluster-bind-address=${IP_V4}"
//                                sh "k3d cluster stop ${CLUSTER_NAME}"
//                                sh "k3d cluster delete ${CLUSTER_NAME}"
                    }
                }
                        
                    
                

                // trivy scan parallel zu test gop

                // stage('Test GOP') {

                    // try install k3d; workspace install; concurrency issues (k3d subnet; k3d clustername); kubeconfig mounten in gop container, docker.sock nicht nötig?
                    //
                    // docker raus aus dockerfile
                    //
                    // finally check k3d binary present -> cluster running -> stop -> destroy;


                    
                // }

                // image pushen
            }
        }
    }
}

String createImageTag() {
    def git = cesBuildLib.Git.new(this)
    String branch = git.simpleBranchName
    String branchSuffix = ""

    if (!"develop".equals(branch)) {
        branchSuffix = "-${branch}"
    }

    return "${new Date().format('yyyyMMddHHmm')}-${git.commitHashShort}${branchSuffix}"
}

def cesBuildLib
