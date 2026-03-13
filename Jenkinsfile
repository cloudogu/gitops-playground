pipeline {
    agent {
        label 'high-cpu'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
    }

    parameters {
        booleanParam(defaultValue: false, name: 'forcePushImage', description: 'Pushes the image with the current git commit as tag, even when it is on a branch')
        choice(name: 'chooseProfile', choices: ['minimal', 'all profiles', 'full', 'full-prefix', 'content-examples', 'operator-full','operator-mandants'], description: 'Starts GOP with given profile only and execute tests which belongs to profile.')
    }

    environment {
        BUILD_USER = sh(script: 'id -u', returnStdout: true).trim()
        BUILD_GROUP = sh(script: 'getent group docker | cut -d: -f3', returnStdout: true).trim()
        DOCKER_REGISTRY_BASE_URL = 'ghcr.io'
        DOCKER_IMAGE_NAME = 'cloudogu/gitops-playground'
        MAVEN_IMAGE = 'maven:3-eclipse-temurin-17'
        TRIVY_IMAGE = 'aquasec/trivy:0.69.3'
        GOLANG_IMAGE = 'golang:1.25-alpine'
        SHORT_SHA = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
        BUILD_DATE = sh(script: 'date --rfc-3339 ns', returnStdout: true).trim()
        K3D_CLUSTER_NAME = "k3d-gop-cluster-${env.BUILD_ID}"
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
                            env.FULL_IMAGE_TAG = "${env.DOCKER_REGISTRY_BASE_URL}/${env.DOCKER_IMAGE_NAME}:${env.SHORT_SHA}"
                            docker.build(env.FULL_IMAGE_TAG,
                                         "--build-arg BUILD_DATE='${env.BUILD_DATE}' " +
                                         "--build-arg VCS_REF='${env.GIT_COMMIT}' " +
                                         "--build-arg ENV=dev ."
                            )
                        }
                    }
                }
            }
        }

        stage('Security and Integration') {

            parallel {

                stage('Trivy Scan') {
                    agent { docker {
                        image "${env.TRIVY_IMAGE}"
                        args "--entrypoint='' -v /var/run/docker.sock:/var/run/docker.sock:ro -u ${env.BUILD_USER}:${env.BUILD_GROUP}"
                        reuseNode true
                    }}
                    steps {
                        sh "trivy image --cache-dir /tmp/.cache --format template --template @/contrib/html.tpl --output trivy_report.html --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db --db-repository public.ecr.aws/aquasecurity/trivy-db --quiet --ignore-unfixed ${env.FULL_IMAGE_TAG}"
                        archiveArtifacts artifacts: 'trivy_report.html'
                    }
                }

                stage('Integration tests') {
                    steps {
                        script {
                            def profiles = (env.BRANCH_NAME == 'feature/weekly-builds')
                                ? ['operator-full']
                                : [params.chooseProfile]

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
                                        sh "/app/scripts/apply-ng.sh --profile=${profile}"
                                    }
                                    docker.image("${env.MAVEN_IMAGE}").inside(dockerArgs) {
                                        sh "mvn -B failsafe:integration-test failsafe:verify -Dmicronaut.environments=${profile} -Dsurefire.reportNameSuffix=${profile}"
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
            steps {
                sh "echo push-image"
            }
        }

    }

    post {
        always {
            node('master') {
                emailext(
                    subject: "Job-Status: ${env.JOB_NAME} - ${env.BUILD_NUMBER}",
                    body: """
                        <p>weitere Infos hier: ${env.BUILD_URL}</p>
                    """,
                    mimeType: 'text/html',
                    recipientProviders: [
                        [$class: 'DevelopersRecipientProvider'],
                        [$class: 'RequesterRecipientProvider']
                    ]
                )
            }
        }
    }
}
