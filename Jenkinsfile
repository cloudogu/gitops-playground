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
        DOCKER_REGISTRY_BASE_URL = "ghcr.io"
        DOCKER_IMAGE_NAME = "cloudogu/gitops-playground"
        MAVEN_IMAGE = "maven:3-eclipse-temurin-17"
        TRIVY_IMAGE = "aquasec/trivy:0.69.3"
        SHORT_SHA = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
        BUILD_DATE = sh(script: 'date --rfc-3339 ns', returnStdout: true).trim()
        K3D_CLUSTER_NAME = "k3d-gop-cluster-${env.BUILD_ID}"
        K3D_NET_NAME = "k3d-gop-net-${env.BUILD_ID}"
        K3D_VOL_NAME = "k3d-gop-vol-${env.BUILD_ID}"
        K3D_IMAGE = "ghcr.io/k3d-io/k3d:latest"
    }

    stages {

        stage('Build') {
            parallel {
                stage("Build CLI") {
                    agent { docker {
                        image "${env.MAVEN_IMAGE}"
                        reuseNode true
                    }}
                    steps {
                        sh "mvn clean test -Dmaven.test.failure.ignore=true"
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
                        sh "trivy image --cache-dir /tmp/.cache --format template --template @/contrib/html.tpl --output trivy_report.html --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db --db-repository public.ecr.aws/aquasecurity/trivy-db --ignore-unfixed ${env.FULL_IMAGE_TAG}"
                        archiveArtifacts artifacts: 'trivy_report.html'
                    }
                }
                stage('Integration tests') {
                    steps {
                        script {
                            sh "KUBECONFIG=.kubeconfig.yaml yes | ./scripts/init-cluster.sh --cluster-name=${env.K3D_CLUSTER_NAME} --bind-registry-port=0 --bind-ingress-port=0"
                        }
                    }
                    post {
                        always {
                            sh "$HOME/.local/bin/k3d cluster delete ${env.K3D_CLUSTER_NAME}"
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
            emailext(
                subject: "Das ist ein Test: ${env.JOB_NAME} - ${env.BUILD_NUMBER}",
                body: """
                     <p>Das ist ein Test: ${env.BUILD_URL}</p>
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
