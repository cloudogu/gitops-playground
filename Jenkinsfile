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
                choice(name: 'chooseProfile', choices: ['minimal', 'all profiles', 'full', 'full-prefix', 'content-examples', 'operator-full','operator-mandants'], description: 'Starts GOP with given profile only and execute tests which belongs to profile.')
        ])
])

// definition of profiles, without 'all'
def predefinedProfiles = ['full', 'full-prefix', 'content-examples', 'minimal',  'operator-full',  'operator-mandants']
def profilesToTest = predefinedProfiles.contains(params.chooseProfile) ? [ params.chooseProfile ] : predefinedProfiles
echo "current profiles to test ${profilesToTest}"
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
                            stageBuildClI()
                        },
                        'Build images': {
                            stage('Build images') {
                                imageNames += createImageName(git.commitHashShort)

                                images += buildImage(imageNames[0], '--build-arg ENV=dev')
                            }
                        }
                )
                parallel(
                        'Scan image': {
                            stage('Scan image') {
                                scanForCriticalVulns(imageNames[0],"prod-criticals")

                                scanForAllVulns(imageNames[0], "prod-all")

                            }
                        },
                        'Integrationtests': {
                            stage('Integrationtests depends on configuraton') {
                                if (env.BRANCH_NAME == 'main') {
                                    // on main all IT test has to run to ensure stability
                                    echo "main branch, testing with full-prefix profile"
                                    executeProfileTestStages(predefinedProfiles)
                                } else {
                                    // this is default!
                                    executeProfileTestStages(profilesToTest)
                                }
                            }
                        }
                )
                stage('Push image') {
                    if (isBuildSuccessful()) {
                        docker.withRegistry("https://${dockerRegistryBaseUrl}", 'cesmarvin-ghcr') {
                            // Push prod image last, because last pushed image is listed on top in GitHub

                            if (git.isTag() && env.BRANCH_NAME == 'main') {
                                // Build tags only on main to avoid human errors

                                images[0].push()
                                images[0].push('latest')
                                images[0].push('main')
                                images[0].push(git.tag)

                                currentBuild.description = createImageName(git.tag)
                                currentBuild.description += "\n${imageNames[0]}"

                            } else if (env.BRANCH_NAME == 'main') {
                                images[0].push()
                                images[0].push('main')
                                currentBuild.description = "${imageNames[0]}"
                            } else if (env.BRANCH_NAME == 'test') {
                                images[0].push()
                                images[0].push('test')
                                currentBuild.description = createImageName('test')
                                currentBuild.description += "\n${imageNames[0]}"
                            } else if (params.forcePushImage) {
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
                    sh "yes | ./scripts/init-cluster.sh --cluster-name=${clusterName} --bind-registry-port=0 --bind-ingress-port=0"
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


/**
    * Loops over all profiles, start K3d, start GOP and executes tests.
    * @param profiles List of profiles to execute tests for.
    */

def executeProfileTestStages(def profiles) {
    // This method represents a stage for executing profile tests.
    // Currently all profile specific tests are executed in the 'profile tests' stage

    echo "Loop over ${profiles} to test."

    profiles.each  { profile ->
        clusterName = createClusterName()

        startK3d(clusterName)

        if (profile.startsWith('operator')) {
            stageInstallArgoCDOperator(clusterName, profile)
        }

        stageStartGOPWithProfile(clusterName, profile)

        stageIntegrationTests(clusterName, profile)

        stageDeleteK3dCluster(clusterName)

    }
}
/**
    * Stage for executing integration tests for a given profile.
    * @param clusterName Name of the k3d cluster.
    * @param profile Profile to execute tests for.
    */
def stageIntegrationTests(String clusterName, String profile) {

    String k3dAddress = sh(
            script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${clusterName}-server-0",
            returnStdout: true
    ).trim()

    String k3dNetwork = sh(
            script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.NetworkID}}{{end}}' k3d-${clusterName}-server-0",
            returnStdout: true
    ).trim()

    withEnv(["KUBECONFIG=${env.WORKSPACE}/.kube/config", "ADDITIONAL_DOCKER_RUN_ARGS=--network=host", "K3D_ADDRESS=${k3dAddress}"]) {
        mvn.useLocalRepoFromJenkins = true
        mvn "failsafe:integration-test -Dmaven.test.failure.ignore=true -Dmicronaut.environments=${profile} -Dsurefire.reportNameSuffix=${profile}"

        junit testResults: "**/target/failsafe-reports/TEST-*${profile}.xml"
    }

}


def stageStartGOPWithProfile(String clusterName, String profile) {
    echo "=========================================="
    echo "Starting profile: ${profile}"
    echo "=========================================="



    String registryPort = sh(
            script: 'docker inspect ' +
                    '--format=\'{{ with (index .NetworkSettings.Ports "30000/tcp") }}{{ (index . 0).HostPort }}{{ end }}\' ' +
                    " k3d-${clusterName}-serverlb",
            returnStdout: true
    ).trim()

    docker.image(imageNames[0])
            .inside("--network=host -e KUBECONFIG=${env.WORKSPACE}/.kube/config --entrypoint=''") {
                sh """
                /app/scripts/apply-ng.sh  \
                    --internal-registry-port=${registryPort} \
                    --insecure \
                    --yes=true \
                    --trace=true \
                    --profile=${profile}
            """
    }
}



def stageDeleteK3dCluster(String clusterName) {
    if (clusterName) {
        // Don't fail build if cleaning up fails
        withEnv(["PATH=${WORKSPACE}/.local/bin:${PATH}"]) {
            sh "if k3d cluster ls ${clusterName} > /dev/null; " +
                    "then k3d cluster delete ${clusterName}; " +
                    "fi"
        }
    }
}

def stageBuildClI() {

    groovyImage = "groovy:jdk17-alpine"
    // Re-use groovy image here, even though we only need JDK
    mvn = new MavenWrapperInDocker(this, groovyImage)
    // Faster builds because mvn local repo is reused between build, unit and integration tests
    mvn.useLocalRepoFromJenkins = true

    mvn 'clean test -Dmaven.test.failure.ignore=true'
    junit testResults: '**/target/surefire-reports/TEST-*.xml'
}
/**
    * Stage for installing ArgoCD Operator in the k3d cluster.
    * @param clusterName Name of the k3d cluster.
    * @param profile Profile to execute tests for.
    */
def stageInstallArgoCDOperator(String clusterName, String profile) {



        String k3dAddress = sh(
            script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${clusterName}-server-0",
            returnStdout: true
        ).trim()

        // Install Argocd operator
        echo "install argocd operator"
        withEnv(["KUBECONFIG=${env.WORKSPACE}/.kube/config", "ADDITIONAL_DOCKER_RUN_ARGS=--network=host", "K3D_ADDRESS=${k3dAddress}"]) {

            docker.image('golang:1.25-alpine').inside('--user root --network=host') {
                sh '''
                apk add --no-cache make bash curl git kubectl
                chmod  +x ./scripts/local/install-argocd-operator.sh
                ./scripts/local/install-argocd-operator.sh
                '''
            }
        }

        echo "install argocd operator is ready"

}
def images
def imageNames
String clusterName
def mvn
String groovyImage
def git