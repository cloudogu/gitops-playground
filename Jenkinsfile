#!groovy
@Library('github.com/cloudogu/ces-build-lib@2.5.0')
import com.cloudogu.ces.cesbuildlib.*

String getDockerRegistryBaseUrl() { 'ghcr.io' }
String getDockerImageName() { 'cloudogu/gitops-playground' }
String getTrivyVersion() { '0.55.0'}

properties([
        // Dont keep builds forever to preserve space
        buildDiscarder(logRotator(numToKeepStr: '50')),

        // For now allow concurrent builds.
        // This is a slight risk of failing builds if two Jobs of the same branch install k3d (workspace-local) at the same time.
        // If this happens to occur often, add the following here: disableConcurrentBuilds(),

        parameters([
                booleanParam(defaultValue: false, name: 'forcePushImage', description: 'Pushes the image with the current git commit as tag, even when it is on a branch'),
                booleanParam(defaultValue: false, name: 'longRunningTests', description: 'Executes long running async integrationtests like testing ArgoCD feature deployment')
        ])
])

node('high-cpu') {

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
                    // Otherwise git.isTag() will not be reliable. Jenkins seems to do a sparse checkout only
                    sh "git fetch --tags"
                }
                parallel (
                        'Build cli': {
                            stage('Build cli') {
                                // Read Java version from Dockerfile (DRY)
                                String jdkVersion = sh(returnStdout: true, script:
                                        'grep -r \'ARG JDK_VERSION\' Dockerfile | sed "s/.*JDK_VERSION=\'\\(.*\\)\'.*/\\1/" ').trim()
                                // Groovy version is defined by micronaut version. Get it from there.
                                String groovyVersion = sh(returnStdout: true, script:
                                        'MICRONAUT_VERSION=$(cat pom.xml | sed -n \'/<parent>/,/<\\/parent>/p\' | ' +
                                                'sed -n \'s/.*<version>\\(.*\\)<\\/version>.*/\\1/p\'); ' +
                                                'curl -s https://repo1.maven.org/maven2/io/micronaut/micronaut-core-bom/${MICRONAUT_VERSION}/micronaut-core-bom-${MICRONAUT_VERSION}.pom | ' +
                                                'sed -n \'s/.*<groovy.version>\\(.*\\)<\\/groovy.version>.*/\\1/p\'').trim()
                                groovyImage = "groovy:${groovyVersion}-jdk${jdkVersion}"
                                // Re-use groovy image here, even though we only need JDK
                                mvn = new MavenWrapperInDocker(this, groovyImage)
                                // Faster builds because mvn local repo is reused between build, unit and integration tests
                                mvn.useLocalRepoFromJenkins = true

                                mvn 'clean test -Dmaven.test.failure.ignore=true'
                                 junit testResults: '**/target/surefire-reports/TEST-*.xml'
                            }
                        },
                        'Build images': {
                            stage('Build images') {
                                imageNames += createImageName(git.commitHashShort)
                                imageNames += createImageName(git.commitHashShort) + '-dev'

                                images += buildImage(imageNames[0])
                                images += buildImage(imageNames[1], '--build-arg ENV=dev')
                            }
                        }
                  )
                parallel(
                        'Scan image': {
                            stage('Scan image') {

                                scanForCriticalVulns(imageNames[0],"prod-criticals")
                                scanForCriticalVulns(imageNames[1], "dev-criticals")

                                scanForAllVulns(imageNames[0], "prod-all")
                                scanForAllVulns(imageNames[1], "dev-all")
                            }
                        },

                        'Start gitops playground': {
                            stage('start gitops playground') {
                                clusterName = createClusterName()
                                startK3d(clusterName)

                                String registryPort = sh(
                                        script: 'docker inspect ' +
                                                '--format=\'{{ with (index .NetworkSettings.Ports "30000/tcp") }}{{ (index . 0).HostPort }}{{ end }}\' ' +
                                                " k3d-${clusterName}-serverlb",
                                        returnStdout: true
                                ).trim()

                                docker.image(imageNames[0])
                                        .inside("-e KUBECONFIG=${env.WORKSPACE}/.kube/config " +
                                                " --network=host --entrypoint=''") {
                                            sh "/app/apply-ng --yes --trace --internal-registry-port=${registryPort} " +
                                                    "--registry --jenkins --content-examples " + 
                                                    "--argocd --monitoring --vault=dev --ingress-nginx --mailhog --base-url=http://localhost --cert-manager"
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

                    int ret = 0


                    // long running can switch on for every branch but should run everytime on MAIN.
                    if (params.longRunningTests || (env.BRANCH_NAME == 'main')) {
                        withEnv([ "KUBECONFIG=${env.WORKSPACE}/.kube/config", "ADDITIONAL_DOCKER_RUN_ARGS=--network=host","K3D_ADDRESS=${k3dAddress}"]) {
                            mvn.useLocalRepoFromJenkins = true
                            mvn 'failsafe:integration-test -Dmaven.test.failure.ignore=true -Plong-running'
                            // Archive test results. Makes build unstable on failed tests.
                            junit testResults: '**/target/failsafe-reports/TEST-*.xml'
                        }
                    } else {

                        withEnv([ "KUBECONFIG=${env.WORKSPACE}/.kube/config", "ADDITIONAL_DOCKER_RUN_ARGS=--network=host","K3D_ADDRESS=${k3dAddress}"]) {
                            mvn.useLocalRepoFromJenkins = true
                            mvn 'failsafe:integration-test -Dmaven.test.failure.ignore=true'
                            // Archive test results. Makes build unstable on failed tests.
                            junit testResults: '**/target/failsafe-reports/TEST-*.xml'
                            }
                    }

                    if (ret > 0 || currentBuild.result == 'UNSTABLE') {
                        if (fileExists('playground-logs-of-failed-jobs')) {
                            archiveArtifacts artifacts: 'playground-logs-of-failed-jobs/*.log'
                        }
                        unstable "Integration tests failed, see logs appended to jobs and cluster status in logs"
                        
                        kubectlToFile(clusterName,"allPods.txt","get all -A")
                        
                        printIntegrationTestLogs(clusterName,'app=scm-manager')
                        printIntegrationTestLogs(clusterName,'app.kubernetes.io/name=jenkins')
                    }
                }

                stage('Push image') {
                    if (isBuildSuccessful()) {
                        docker.withRegistry("https://${dockerRegistryBaseUrl}", 'cesmarvin-ghcr') {
                            // Push prod image last, because last pushed image is listed on top in GitHub
                            
                            if (git.isTag() && env.BRANCH_NAME == 'main') {
                                // Build tags only on main to avoid human errors
                                
                                images[1].push()
                                images[1].push(git.tag + '-dev')
                                images[1].push('dev')
                                images[1].push('latest-dev')
                                images[1].push('main-dev')
                                images[0].push()
                                images[0].push('latest')
                                images[0].push('main')
                                images[0].push(git.tag)

                                currentBuild.description = createImageName(git.tag)
                                currentBuild.description += "\n${imageNames[0]}"

                            } else if (env.BRANCH_NAME == 'main') {
                                images[1].push()
                                images[1].push('main-dev')
                                images[0].push()
                                images[0].push('main')
                                currentBuild.description = "${imageNames[0]}"
                            } else if (env.BRANCH_NAME == 'test') {
                                images[1].push()
                                images[1].push('test-dev')
                                images[0].push()
                                images[0].push('test')
                                currentBuild.description = createImageName('test')
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
            if (clusterName) {
                // Don't fail build if cleaning up fails
                withEnv(["PATH=${WORKSPACE}/.local/bin:${PATH}"]) {
                    sh "if k3d cluster ls ${clusterName} > /dev/null; " +
                            "then k3d cluster delete ${clusterName}; " +
                        "fi"
                }
            }
        }

        mailIfStatusChanged(git.commitAuthorEmail)
        
        if (env.BRANCH_NAME == 'main' && env.GOP_DEVELOPERS) {
            mailIfStatusChanged(env.GOP_DEVELOPERS)
        }
    }
}

def buildImage(String imageName, String additionalBuildArgs = '') {
    String rfcDate = sh(returnStdout: true, script: 'date --rfc-3339 ns').trim()
    return docker.build(imageName,
            "--build-arg BUILD_DATE='${rfcDate}' " +
                    "--build-arg VCS_REF='${git.commitHash}' " +
                    "${additionalBuildArgs} " +
                    // if using optional parameters you need to add the '.' argument at the end for docker to build the image
                    ".")
}

def scanForCriticalVulns(String imageName, String fileName){
    def additionalTrivyConfig = [
            severity       : ['CRITICAL'],
            additionalFlags: '--ignore-unfixed',
    ]

    def vulns = trivy(imageName, additionalTrivyConfig)

    if (vulns.size() > 0) {
        writeFile(file: ".trivy/${fileName}.json", encoding: "UTF-8", text: readFile(file: '.trivy/trivyOutput.json', encoding: "UTF-8"))
        archiveArtifacts artifacts: ".trivy/${fileName}.json"
        unstable "Found  ${vulns.size()} vulnerabilities in image. See ${fileName}.json"
    }
}

def scanForAllVulns(String imageName, String fileName){
    def vulns = trivy(imageName)

    if (vulns.size() > 0) {
        writeFile(file: ".trivy/${fileName}.json", encoding: "UTF-8", text: readFile(file: '.trivy/trivyOutput.json', encoding: "UTF-8"))
        archiveArtifacts artifacts: ".trivy/${fileName}.json"
    }
}

def trivy(String imageName, Map additionalTrivyConfig = [:]) {
    def trivyConfig = [
            imageName      : imageName,
            trivyVersion   : trivyVersion,
            additionalFlags: ''
    ]
    trivyConfig.putAll(additionalTrivyConfig)
    trivyConfig.additionalFlags += ' --db-repository public.ecr.aws/aquasecurity/trivy-db'
    trivyConfig.additionalFlags += ' --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db'

    findVulnerabilitiesWithTrivy(trivyConfig)

}

def printIntegrationTestLogs(String clusterName, String appSelector, String namespace = 'default'){
    def filename=appSelector.split("=")

    kubectlToFile(clusterName, "${filename[1]}.log", "logs -n ${namespace} -l ${appSelector} --tail=-1")
    kubectlToFile(clusterName, "${filename[1]}-previous.log", "logs -n ${namespace} -l ${appSelector} --tail=-1 -p")
    kubectlToFile(clusterName, "${filename[1]}-describe.txt", "describe pods -n ${namespace} -l ${appSelector}")
}

void kubectlToFile(String clusterName, String filename, String command) {
    sh "docker exec k3d-${clusterName}-server-0 kubectl ${command} | tee ${filename}"
    archiveArtifacts artifacts: "${filename}", allowEmptyArchive: true
}

def startK3d(clusterName) {
    
    // Download latest version of static curl, needed insight the container bellow.
    sh "mkdir -p $WORKSPACE/.local/bin"
    sh(returnStdout: true, script: 'curl -sLo .local/bin/curl ' +
            // Note that the repo moparisthebest/static-curl is listed on the official page, so it should be trustworthy
            '$(curl -sL -I -o /dev/null -w %{url_effective} https://github.com/moparisthebest/static-curl/releases/latest ' +
                '| sed "s/tag/download/")/curl-amd64 && ' +
            'chmod +x .local/bin/curl'
       ).trim()
    
    // Start k3d in a bash3 container to make sure we're OSX compatible 😞
    new Docker(this).image('bash:3')
            .mountDockerSocket()
            .installDockerClient()
            .inside() {
                withEnv([
                        // Install k3d to WORSKPACE and make k3d write kubeconfig to WORKSPACE
                        "HOME=${WORKSPACE}",
                        // Put k3d and curl on the path
                         "PATH=${WORKSPACE}/.local/bin:${PATH}"]) {

                    // Start k3d cluster, binding to an arbitrary registry port
                    sh "yes | ./scripts/init-cluster.sh --bind-ingress-port=0 --cluster-name=${clusterName} --bind-registry-port=0"
                }
            }
}

String createClusterName() {
    String[] randomUUIDs = UUID.randomUUID().toString().split("-")
    String uuid = randomUUIDs[randomUUIDs.length - 1]
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