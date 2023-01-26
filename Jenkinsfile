#!groovy
@Library('github.com/cloudogu/ces-build-lib@1.48.0')
import com.cloudogu.ces.cesbuildlib.*

String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerImageName() { 'cloudogu/gitops-playground' }
// Note that from 0.30.x the resulting file will never be 0 kb in size, as checked in saveScanResultsOnVulnerabilities()
String getTrivyVersion() { '0.29.2' }

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

    git = new Git(this)
    // Avoid 'No such property: clusterName' error in 'Stop k3d' stage in builds that have failed in an early stage
    clusterName = ''
    images = []
    imageNames = []
    
    timestamps {
        catchError {
            timeout(activity: false, time: 60, unit: 'MINUTES') {

                stage('Checkout') {
                    checkout scm
                    git.clean('')
                }

                stage('Build cli') {
                    // Read version from Dockerfile (DRY)
                    String jdkVersion = sh (returnStdout: true, script: 'grep -r \'ARG JDK_VERSION\' Dockerfile | sed "s/.*JDK_VERSION=\'\\(.*\\)\'.*/\\1/" ').trim()
                    String groovyVersion = sh (returnStdout: true, script: 'grep -r \'ARG GROOVY_VERSION\' Dockerfile | sed "s/.*GROOVY_VERSION=\'\\(.*\\)\'.*/\\1/" ').trim()
                    groovyImage = "groovy:${groovyVersion}-jdk${jdkVersion}"
                    // Re-use groovy image here, even though we only need JDK
                    mvn = new MavenWrapperInDocker(this, groovyImage)
                    
                    mvn 'clean install -DskipTests'
                }

                // This interferes with the e2etests regarding the dependency download of grapes. It might be that the MavenWrapperInDocker somehow interferes with the FileSystem so that grapes is no longer to write to fs.
                stage('Test cli') {
                    mvn 'test -Dmaven.test.failure.ignore=true'
                    // Archive test results. Makes build unstable on failed tests.
                    junit testResults: '**/target/surefire-reports/TEST-*.xml'
                }

                stage('Build images') {
                    imageNames += createImageName(git.commitHashShort)
                    imageNames += createImageName(git.commitHashShort) + '-dev' 
                    
                    images += buildImage(imageNames[0])
                    images += buildImage(imageNames[1], '--build-arg ENV=dev')
                }
                
                parallel(
                        'Scan image': {
                            stage('Scan image') {
                                scanImage(imageNames[0])
                                saveScanResultsOnVulnerabilities()
                                
                                scanImage(imageNames[1], '-dev')
                                saveScanResultsOnVulnerabilities('-dev')
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

                                docker.image(imageNames[0])
                                        .inside("-e KUBECONFIG=${env.WORKSPACE}/.kube/config " +
                                                " --network=host --entrypoint=''" ) {
                                            sh "/app/scripts/apply.sh --yes --trace --internal-registry-port=${registryPort} --argocd --monitoring --vault=dev"
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
                            // Push prod image last, because last pushed image is listed on top in GitHub
                            if (git.isTag()) {
                                images[1].push()
                                images[1].push(git.tag + '-dev')
                                images[0].push()
                                images[0].push(git.tag)
                                
                                currentBuild.description = createImageName(git.tag)
                                currentBuild.description += "\n${imageNames[0]}"

                            } else if (env.BRANCH_NAME == 'main') {
                                images[1].push()
                                images[1].push('dev')
                                images[1].push('latest-dev')
                                images[0].push()
                                images[0].push('latest')
                                currentBuild.description = createImageName('latest')
                                currentBuild.description += "\n${imageNames[0]}"
                            } else if (params.forcePushImage) {
                                images[1].push()
                                images[0].push()
                                currentBuild.description = imageNames[0]
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

def buildImage(String imageName, String additionalBuildArgs='') {
    String rfcDate = sh(returnStdout: true, script: 'date --rfc-3339 ns').trim()
    return docker.build(imageName,
            "--build-arg BUILD_DATE='${rfcDate}' " +
                    "--build-arg VCS_REF='${git.commitHash}' " +
                    "${additionalBuildArgs} " +
                    // if using optional parameters you need to add the '.' argument at the end for docker to build the image
                    ".")
}

def scanImage(String imageName, String suffix='') {
    trivy(".trivy/trivyOutput${suffix}-fixable.txt", '--severity=CRITICAL --ignore-unfixed', imageName)
    trivy(".trivy/trivyOutput${suffix}-all.txt", '', imageName)
}

private void trivy(output, flags, imageName) {
    sh 'mkdir -p .trivy/.cache'
    new Docker(this).image("aquasec/trivy:${trivyVersion}")
            .mountJenkinsUser()
            .mountDockerSocket()
            .inside("-v ${env.WORKSPACE}/.trivy/.cache:/root/.cache/") {
                // Scanning occasionally take longer than the default 5 min, increase timeout
                // Avoid timouts with offline-scan. This does not affect updates of the trivy DB 
                // https://github.com/aquasecurity/trivy/issues/3421
                sh "trivy -d image --offline-scan --timeout 30m -o ${output} ${flags} ${imageName}"
            }
}

def saveScanResultsOnVulnerabilities(String suffix='') {
    if (readFile(".trivy/trivyOutput${suffix}-all.txt").size() != 0) {
        archiveArtifacts artifacts: ".trivy/trivyOutput${suffix}-all.txt"
    }
    if (readFile(".trivy/trivyOutput${suffix}-fixable.txt").size() != 0) {
        archiveArtifacts artifacts: ".trivy/trivyOutput${suffix}-fixable.txt"
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

def images
def imageNames
String clusterName
def mvn
String groovyImage
def git