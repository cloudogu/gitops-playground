#!groovy
@Library('github.com/cloudogu/ces-build-lib@1.48.0')
import com.cloudogu.ces.cesbuildlib.*

String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerImageName() { 'cloudogu/gitops-playground' }
String getTrivyVersion() { '0.18.3' }
String getGroovyImage() { 'groovy:3.0.8-jre11' }

properties([
    // Dont keep builds forever to preserve space
    buildDiscarder(logRotator(numToKeepStr: '50')),

    // For now allow concurrent builds.
    // This is a slight risk of failing builds if two Jobs of the same branch install k3d (workspace-local) at the same time.
    // If this happens to occur often, add the following here: disableConcurrentBuilds(),

    parameters([
        booleanParam(defaultValue: false, name: 'forcePushImage', description: 'Pushes the image with the current git commit as tag, even when it is on a branch')
    ])
])

node('docker') {

    def git = new Git(this)
    def mvn = new MavenWrapperInDocker(this, 'azul/zulu-openjdk-alpine:17.0.2')
    // Avoid 'No such property: clusterName' error in 'Stop k3d' stage in builds that have failed in an early stage
    clusterName = ''
    
    timestamps {
        catchError {
            timeout(activity: false, time: 60, unit: 'MINUTES') {

                stage('Checkout') {
                    checkout scm
                    git.clean('')
                }

                stage('Build cli') {
                    mvn 'clean install -DskipTests'
                }

                // This interferes with the e2etests regarding the dependency download of grapes. It might be that the MavenWrapperInDocker somehow interferes with the FileSystem so that grapes is no longer to write to fs.
                stage('Test cli') {
                    mvn 'test -Dmaven.test.failure.ignore=true'
                    // Archive test results. Makes build unstable on failed tests.
                    junit testResults: '**/target/surefire-reports/TEST-*.xml'
                }

                stage('Build image') {
                    imageName = createImageName(git.commitHashShort)
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

                                String registryPort = sh(
                                        script: 'docker inspect ' +
                                                '--format=\'{{ with (index .NetworkSettings.Ports "30000/tcp") }}{{ (index . 0).HostPort }}{{ end }}\' ' +
                                                " k3d-${clusterName}-server-0",
                                        returnStdout: true
                                ).trim()

                                docker.image(imageName)
                                        .inside("-e KUBECONFIG=${env.WORKSPACE}/.kube/config " +
                                                " --network=host --entrypoint=''" ) {
                                            sh "/app/scripts/apply.sh --yes --trace --internal-registry-port=${registryPort} --argocd"
                                        }
                            }
                        }
                )

                stage('Integration test') {

                    String k3dAddress = sh(
                            script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${clusterName}-server-0",
                            returnStdout: true
                    ).trim()

                    String k3dNetwork = sh(
                            script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.NetworkID}}{{end}}' k3d-${clusterName}-server-0",
                            returnStdout: true
                    ).trim()

                    new Docker(this).image(groovyImage)
                            // Avoids errors ("unable to resolve class") probably due to missing HOME for container in JVM.
                            .mountJenkinsUser() 
                            .inside("--network=${k3dNetwork}") {
                                // removing m2 and grapes avoids issues where grapes primarily resolves local m2 and fails on missing versions
                                sh "rm -rf .m2/"
                                sh "rm -rf .groovy/grapes"
                                sh "groovy ./scripts/e2e.groovy --url http://${k3dAddress}:9090 --user admin --password admin --writeFailedLog --fail --retry 2"
                    }
                }
               stage('Push image') {
                    if (isBuildSuccessful()) {
                        docker.withRegistry("https://${dockerRegistryBaseUrl}", 'cesmarvin-github') {
                            if (git.isTag()) {
                                image.push()
                                image.push(git.tag)
                                currentBuild.description = createImageName(git.tag)
                                currentBuild.description += "\n${imageName}"

                            } else if (env.BRANCH_NAME == 'main') {
                                image.push()
                                image.push("latest")
                                currentBuild.description = createImageName("latest")
                                currentBuild.description += "\n${imageName}"
                            } else if (params.forcePushImage) {
                                image.push()
                                currentBuild.description = imageName
                            } else {
                                echo "Skipping deployment to github container registry because not a tag and not main branch."
                            }
                        }
                    }
                }
            }
        }

        stage('Stop k3d') {
            // saving log artifacts is handled here since the failure of the integration test step leads directly here.
            if (fileExists('playground-logs-of-failed-jobs')) {
                archiveArtifacts artifacts: 'playground-logs-of-failed-jobs/*.log'
            }

            if (clusterName) {
                // Don't fail build if cleaning up fails
                withEnv(["PATH=${WORKSPACE}/.k3d/bin:${PATH}"]) {
                    sh "k3d cluster delete ${clusterName} || true"
                }
            }
        }

        mailIfStatusChanged(git.commitAuthorEmail)
    }
}

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
    sh "mkdir -p ${WORKSPACE}/.k3d/bin"
    
    withEnv(["HOME=${WORKSPACE}", "PATH=${WORKSPACE}/.k3d/bin:${PATH}"]) { // Make k3d write kubeconfig to WORKSPACE
        // Install k3d binary to workspace in order to avoid concurrency issues
        sh "if ! command -v k3d >/dev/null 2>&1; then " +
                "curl -s https://raw.githubusercontent.com/rancher/k3d/main/install.sh |" +
                  'TAG=v$(sed -n "s/^K3D_VERSION=//p" scripts/init-cluster.sh) ' +
                  "K3D_INSTALL_DIR=${WORKSPACE}/.k3d/bin " +
                     'bash -s -- --no-sudo; fi'
        sh "yes | ./scripts/init-cluster.sh --cluster-name=${clusterName} --bind-localhost=false"
    }
}

String createClusterName() {
    String[] randomUUIDs = UUID.randomUUID().toString().split("-")
    String uuid = randomUUIDs[randomUUIDs.length-1]
    return "citest-" + uuid
}

String createImageName(String tag) {
    return "${dockerRegistryBaseUrl}/${dockerImageName}:${tag}"
}

def image
String imageName
String clusterName