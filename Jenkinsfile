pipeline {
    agent {
        label 'high-cpu'
    }

    triggers {
        cron(env.BRANCH_NAME == 'main' ? '0 19 * * 5' : '')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
        timestamps()
        timeout(time: 120, unit: 'MINUTES')
    }

    parameters {
        booleanParam(defaultValue: false, name: 'forcePushImage', description: 'Pushes the image with the current git commit as tag, even when it is on a branch')
        choice(name: 'chooseProfile', choices: ['minimal', 'all-profiles', 'full', 'full-prefix', 'content-examples', 'operator-full','operator-mandants'], description: 'Starts GOP with given profile only and execute tests which belongs to profile.')
    }

    environment {
        BUILD_USER = sh(script: 'id -u', returnStdout: true).trim()
        BUILD_GROUP = sh(script: 'getent group docker | cut -d: -f3', returnStdout: true).trim()
        DOCKER_REGISTRY_BASE_URL = 'ghcr.io'
        DOCKER_IMAGE_NAME = 'cloudogu/gitops-playground'
        MAVEN_IMAGE = 'maven:3-eclipse-temurin-17'
        GRYPE_IMAGE = 'anchore/grype:v0.109.1'
        SYFT_IMAGE = 'anchore/syft:v1.42.2'
        GOLANG_IMAGE = 'golang:1.25-alpine'
        SHORT_SHA = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
        BUILD_DATE = sh(script: 'date --rfc-3339 ns', returnStdout: true).trim()
        K3D_CLUSTER_NAME = "k3d-gop-cluster-${env.BUILD_ID}"
        FULL_IMAGE_TAG = "${env.DOCKER_REGISTRY_BASE_URL}/${env.DOCKER_IMAGE_NAME}:${env.SHORT_SHA}"
        TAG_NAME = sh(returnStdout: true, script: "git --no-pager tag --points-at HEAD").trim()
    }

    stages {

        stage('Build') {

            parallel {

                stage("Build CLI") {
                    agent { docker {
                        image "${env.MAVEN_IMAGE}"
                        args "-v maven-cache:/root/.m2"
                        reuseNode true
                    }}
                    steps {
                        sh 'mvn -B clean test'
                        junit testResults: '**/target/surefire-reports/TEST-*.xml'
                        archiveArtifacts artifacts: "**/target/site/jacoco/index.html"
                    }
                }

                stage("Build Image") {
                    steps {
                        script {
                            docker.build(env.FULL_IMAGE_TAG,
                                         "--build-arg BUILD_DATE='${env.BUILD_DATE}' " +
                                         "--build-arg VCS_REF='${env.GIT_COMMIT}' ."
                            )
                        }
                    }
                }
            }
        }

        stage('Security & Integration') {

            parallel {

                stage('SBOM & Vulnerability Scan') {
                    steps {
                        sh '''docker run --rm -v $WORKSPACE:/workspace \
                                         -v /var/run/docker.sock:/var/run/docker.sock:ro \
                                         -u :$BUILD_GROUP \
                                         -e NO_COLOR=1 \
                                         $SYFT_IMAGE --output syft-table=/workspace/sbom.txt --output spdx-json=/workspace/sbom.json --quiet $FULL_IMAGE_TAG'''
                        sh '''docker run --rm -v $WORKSPACE:/workspace \
                                         -v /var/run/docker.sock:/var/run/docker.sock:ro \
                                         -u :$BUILD_GROUP \
                                         -e NO_COLOR=1 \
                                         $GRYPE_IMAGE sbom:/workspace/sbom.json \
                                             --output table=/workspace/vulnerabilities.txt \
                                             --output sarif=/workspace/vulnerabilities.sarif \
                                             --quiet --sort-by severity --fail-on critical'''
                        archiveArtifacts artifacts: 'sbom.*, vulnerabilities.*'
                    }
                }

                stage('Integration tests') {
                    steps {
                        script {
                            def profiles = (env.BRANCH_NAME == 'main')
                                ? ['full-prefix', 'operator-mandants', 'operator-full']
                                : [params.chooseProfile]

                            def isTriggeredByTimer = currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').size() > 0

                            if (isTriggeredByTimer || params.chooseProfile == 'all-profiles') {
                                profiles = ['minimal', 'full', 'full-prefix', 'content-examples', 'operator-full','operator-mandants']
                            }

                            def dockerArgs = """
                                -e KUBECONFIG=${env.WORKSPACE}/.kubeconfig.yaml
                                -v maven-cache:/root/.m2
                                -v /var/run/docker.sock:/var/run/docker.sock
                                -u :${env.BUILD_GROUP}
                                --network=host
                                --entrypoint ''
                            """

                            def withK3dCluster = { body ->
                                try {
                                    sh "yes | KUBECONFIG=${env.WORKSPACE}/.kubeconfig.yaml ./scripts/init-cluster.sh --cluster-name=${env.K3D_CLUSTER_NAME}"
                                    body()
                                } finally {
                                    sh "KUBECONFIG=${env.WORKSPACE}/.kubeconfig.yaml $HOME/.local/bin/k3d cluster delete ${env.K3D_CLUSTER_NAME}"
                                }}

                            profiles.each { profile ->
                                withK3dCluster {

                                    if (profile.startsWith('operator')) {
                                        docker.image("${env.GOLANG_IMAGE}").inside(dockerArgs) {
                                            sh 'apk add --no-cache make bash curl git kubectl && ./scripts/local/install-argocd-operator.sh'
                                        }
                                    }

                                    docker.image("${env.FULL_IMAGE_TAG}").inside(dockerArgs) {
                                        sh "java -jar /app/gitops-playground.jar --profile=${profile}"
                                    }
                                    docker.image("${env.MAVEN_IMAGE}").inside(dockerArgs) {
                                        sh "mvn -B failsafe:integration-test failsafe:verify -Dmicronaut.environments=${profile} -Dsurefire.reportNameSuffix=${profile} && chown $BUILD_USER:$BUILD_GROUP ./* -R"
                                    }
                                }
                                junit testResults: "**/target/failsafe-reports/TEST-*${profile}.xml",
                                    allowEmptyResults: true
                            }
                        }
                    }
                }
            }
        }

        stage('Push Image') {
            when {
                anyOf {
                    branch 'main'
                    buildingTag()
                    expression { return params.forcePushImage }
                }
                not {
                    triggeredBy 'TimerTrigger'
                }
            }
            steps {
                script {
                    def image = docker.image(env.FULL_IMAGE_TAG)

                    docker.withRegistry("https://${DOCKER_REGISTRY_BASE_URL}", 'cesmarvin-ghcr') {

                        currentBuild.description = "Image: ${env.FULL_IMAGE_TAG}"
                        image.push()

                        if (params.forcePushImage) { image.push(env.BRANCH_NAME) }
                        if (env.TAG_NAME) {
                            image.push('latest')
                            currentBuild.description += "\nImage: ${env.DOCKER_REGISTRY_BASE_URL}/${env.DOCKER_IMAGE_NAME}:latest"
                            currentBuild.description += "\nRelease: ${env.TAG_NAME}"
                        }
                    }
                }
            }
        }

    }

    post {
        changed {
            emailext(
                subject: "${currentBuild.result}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: '${SCRIPT, template="groovy-html.template"}',
                mimeType: 'text/html',
                recipientProviders: [
                    [$class: 'DevelopersRecipientProvider'],
                    [$class: 'RequesterRecipientProvider']
                ]
            )
        }
    }
}