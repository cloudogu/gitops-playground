pipeline {
    agent {
        label 'high-cpu'
    }

    triggers {
        cron(env.BRANCH_NAME == 'develop' ? '0 19 * * 5' : '')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
        timeout(time: 120, unit: 'MINUTES')
    }

    parameters {
        booleanParam(defaultValue: false, name: 'forcePushImage', description: 'Pushes the image with the current git commit as tag, even when it is on a branch')
        booleanParam(defaultValue: false, name: 'noCache', description: 'Builds the docker image without cache')
        choice(name: 'chooseProfile', choices: ['full', 'full-netpols', 'full-secrets', 'minimal', 'all-profiles', 'full-prefix', 'content-examples', 'operator-full', 'operator-mandants'], description: 'Starts GOP with given profile only and execute tests which belongs to profile.')
        booleanParam(defaultValue: false, name: 'performRelease', description: 'Creates a GitHub Release: tags HEAD, publishes SBOM assets to the release and pushes the image to GHCR.')
        string(defaultValue: '', name: 'releaseVersion', description: 'Version to release, schema x.y.z. Leave empty to use the suggested next patch version. Only used when performRelease is checked.')
        booleanParam(defaultValue: false, name: 'skipIntegrationTests', description: 'Skips the integration tests stage')
    }

    environment {
        BUILD_USER = sh(script: 'id -u', returnStdout: true).trim()
        BUILD_GROUP = sh(script: 'getent group docker | cut -d: -f3', returnStdout: true).trim()
        DOCKER_REGISTRY_BASE_URL = 'ghcr.io'
        GITHUB_REPOSITORY = sh(script: "git remote get-url origin | sed -E 's#^(git@github.com:|https://github.com/)##; s#[.]git\$##'", returnStdout: true).trim()
        DOCKER_IMAGE_NAME = "${env.GITHUB_REPOSITORY}"
        MAVEN_IMAGE = 'maven:3-eclipse-temurin-25'
        GRYPE_IMAGE = 'anchore/grype:v0.109.1'
        SYFT_IMAGE = 'anchore/syft:v1.42.2'
        ORAS_IMAGE = 'ghcr.io/oras-project/oras:v1.3.4'
        GOLANG_IMAGE = 'golang:1.25-alpine'
        SHORT_SHA = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
        BUILD_DATE = sh(script: 'date --rfc-3339 ns', returnStdout: true).trim()
        K3D_CLUSTER_NAME = "k3d-gop-cluster-${env.BUILD_ID}"
        FULL_IMAGE_TAG = "${env.DOCKER_REGISTRY_BASE_URL}/${env.DOCKER_IMAGE_NAME}:${env.SHORT_SHA}"
        // Shared docker args for integration tests
        INTEGRATION_TEST_DOCKER_ARGS = "-e KUBECONFIG=${env.WORKSPACE}/.kubeconfig.yaml -v maven-cache:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -u :${env.BUILD_GROUP} --network=host --entrypoint ''"
    }

    stages {

        stage('Resolve tag') {
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'cesmarvin',
                        usernameVariable: 'SCM_USERNAME',
                        passwordVariable: 'SCM_TOKEN'
                )]) {
                    sh "git -c credential.helper='!f() { echo username=\$SCM_USERNAME; echo password=\$SCM_TOKEN; }; f' fetch --tags"
                    // Ask the remote directly, so the result does not depend on the state of the local checkout.
                    script {
                        env.LATEST_TAG = sh(script: "git -c credential.helper='!f() { echo username=\$SCM_USERNAME; echo password=\$SCM_TOKEN; }; f' ls-remote --tags --refs origin | sed 's#.*refs/tags/##' | grep -E '^[0-9]+\\.[0-9]+\\.[0-9]+\$' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1", returnStdout: true).trim()
                    }
                }
                script {
                    def latestTag = env.LATEST_TAG
                    def suggestedNext = nextPatchVersion(latestTag)

                    refreshParameters(latestTag)

                    if (params.performRelease) {
                        env.TAG_NAME = params.releaseVersion?.trim() ?: suggestedNext
                        if (!(env.TAG_NAME ==~ "^\\d+\\.\\d+\\.\\d+\$")) {
                            error("Release version '${env.TAG_NAME}' must match schema x.y.z")
                        }
                        if (sh(script: "git tag --list '${env.TAG_NAME}'", returnStdout: true).trim()) {
                            error("Tag ${env.TAG_NAME} already exists - aborting release build.")
                        }
                        echo "Releasing version: ${env.TAG_NAME}"
                    } else {
                        env.TAG_NAME = sh(returnStdout: true, script: 'git --no-pager tag --points-at HEAD').trim()
                        echo "Resolved tag: ${env.TAG_NAME ?: 'none'}"
                    }
                }
            }
        }

        stage('Build') {

            parallel {

                stage("Test & SonarScanner") {
                    agent {
                        docker {
                            image "${env.MAVEN_IMAGE}"
                            args "-e HOME=${env.WORKSPACE}/.maven-home"
                            reuseNode true
                        }
                    }
                    steps {
                        withSonarQubeEnv('ces-sonar') {
                            sh '''
                                mkdir -p "$WORKSPACE/.maven-home/.m2/repository"

                                mvn -B \
                                    -Dmaven.repo.local="$WORKSPACE/.maven-home/.m2/repository" \
                                    clean verify sonar:sonar \
                                    -Dsonar.projectKey=gitops-playground \
                                    -Dsonar.branch.name="$BRANCH_NAME"
                            '''
                        }
                    }
                    post {
                        always {
                            junit testResults: '**/target/surefire-reports/TEST-*.xml'
                        }
                    }
                }

                stage("Build Image") {
                    steps {
                        script {
                            def buildArgs = (params.noCache ? "--no-cache " : "") +
                                    "--build-arg BUILD_DATE='${env.BUILD_DATE}' " +
                                    "--build-arg VCS_REF='${env.GIT_COMMIT}' "
                            docker.build(env.FULL_IMAGE_TAG, "${buildArgs} .")
                        }
                    }
                }
            }
        }

        stage('Security & Integration') {

            parallel {
                stage('SBOM & Vulnerability Scan') {
                    steps {
                        sh '''docker run --rm \
                                -v "$WORKSPACE:/workspace" \
                                -v /var/run/docker.sock:/var/run/docker.sock:ro \
                                -u ":$BUILD_GROUP" \
                                -e NO_COLOR=1 \
                                "$SYFT_IMAGE" \
                                   --output table=/workspace/sbom.txt \
                                   --output json=/workspace/sbom.syft.json \
                                   --output cyclonedx-json@1.6=/workspace/sbom.cdx.json \
                                   --quiet \
                                   "$FULL_IMAGE_TAG"
                        '''
                        sh 'sha256sum sbom.cdx.json > sbom.cdx.json.sha256'

                        catchError(
                                buildResult: 'SUCCESS',
                                stageResult: 'UNSTABLE',
                                catchInterruptions: false
                        ) {
                            sh '''docker run --rm -v $WORKSPACE:/workspace \
                                         -v /var/run/docker.sock:/var/run/docker.sock:ro \
                                         -u :$BUILD_GROUP \
                                         -e NO_COLOR=1 \
                                         $GRYPE_IMAGE sbom:/workspace/sbom.json \
                                             --output table=/workspace/vulnerabilities.txt \
                                             --output sarif=/workspace/vulnerabilities.sarif \
                                             --sort-by severity --fail-on critical'''
                        }

                        archiveArtifacts(artifacts: 'sbom.*, vulnerabilities.*', allowEmptyArchive: false)
                    }
                }

                stage('Integration tests') {
                    when {
                        expression { return !params.skipIntegrationTests }
                    }
                    steps {
                        script {
                            def profiles = []

                            if (isTriggeredByTimer()
                                    || params.chooseProfile == 'all-profiles'
                                    || (env.BRANCH_NAME == 'main' && !isTriggeredByUser())) {
                                profiles = ['minimal', 'full', 'full-secrets', 'full-prefix', 'content-examples', 'operator-full', 'operator-mandants']
                            } else if (env.BRANCH_NAME == 'develop') {
                                profiles = ['full-prefix', 'operator-mandants', 'operator-full']
                            } else {
                                profiles = [params.chooseProfile]
                            }

                            def dumpKubernetesDebugInfo = { profile ->
                                def dumpDir = "target/k8s-debug/${profile}"

                                docker.image("${env.GOLANG_IMAGE}").inside(env.INTEGRATION_TEST_DOCKER_ARGS) {
                                    sh(script: """
                                        set +e
                                        apk add --no-cache kubectl
                                        mkdir -p '${dumpDir}'
                                        export KUBECONFIG='${env.WORKSPACE}/.kubeconfig.yaml'

                                        kubectl cluster-info > '${dumpDir}/cluster-info.txt' 2>&1
                                        kubectl get nodes -o wide > '${dumpDir}/nodes.txt' 2>&1
                                        kubectl get namespaces -o wide > '${dumpDir}/namespaces.txt' 2>&1
                                        kubectl get pods -A -o wide > '${dumpDir}/pods.txt' 2>&1
                                        kubectl get events -A --sort-by=.lastTimestamp > '${dumpDir}/events.txt' 2>&1
                                        kubectl get pvc,pv -A -o wide > '${dumpDir}/volumes.txt' 2>&1
                                        kubectl get ingress -A -o wide > '${dumpDir}/ingress.txt' 2>&1
                                        kubectl get networkpolicy -A -o yaml > '${dumpDir}/network-policies.yaml' 2>&1
                                        kubectl describe all -A > '${dumpDir}/describe-all.txt' 2>&1

                                        : > '${dumpDir}/container-logs.txt'
                                        kubectl get pods -A --no-headers \\
                                          -o custom-columns=NAMESPACE:.metadata.namespace,NAME:.metadata.name |
                                        while read -r namespace pod; do
                                          echo "===== \${namespace}/\${pod} current =====" >> '${dumpDir}/container-logs.txt'
                                          kubectl logs -n "\${namespace}" "\${pod}" --all-containers=true --tail=200 --prefix=true >> '${dumpDir}/container-logs.txt' 2>&1

                                          echo >> '${dumpDir}/container-logs.txt'
                                          echo "===== \${namespace}/\${pod} previous =====" >> '${dumpDir}/container-logs.txt'
                                          kubectl logs -n "\${namespace}" "\${pod}" --all-containers=true --previous --tail=200 --prefix=true >> '${dumpDir}/container-logs.txt' 2>&1
                                          echo >> '${dumpDir}/container-logs.txt'
                                        done

                                        chown -R ${env.BUILD_USER}:${env.BUILD_GROUP} target
                                    """, returnStatus: true)
                                }

                                archiveArtifacts artifacts: "${dumpDir}/**", allowEmptyArchive: true
                            }

                            def withK3dCluster = { profile, body ->
                                try {
                                    sh "yes | KUBECONFIG=${env.WORKSPACE}/.kubeconfig.yaml ./scripts/init-cluster.sh --cluster-name=${env.K3D_CLUSTER_NAME}"
                                    body()
                                } catch (Throwable t) {
                                    dumpKubernetesDebugInfo(profile)
                                    throw t
                                } finally {
                                    sh "KUBECONFIG=${env.WORKSPACE}/.kubeconfig.yaml $HOME/.local/bin/k3d cluster delete ${env.K3D_CLUSTER_NAME}"
                                }
                            }

                            def createNetworkPolicyIntegrationConfig = {
                                def configFile = 'target/integration-test-config/network-policy.yaml'
                                sh 'mkdir -p target/integration-test-config'
                                writeFile file: configFile, text: """application:
  networkPolicies:
    bootstrapCidrs:
      # Test-only: the GOP container uses host networking and the source address depends on the CI runner network setup.
      - 0.0.0.0/0
    registryAccessCidrs:
      # Test-only: k3d/Docker NAT rewrites the registry source address.
      - 0.0.0.0/0
"""
                                return configFile
                            }

                            def createExternalVaultIntegrationConfig = { vaultServer ->
                                def configFile = 'target/integration-test-config/external-vault.yaml'
                                sh 'mkdir -p target/integration-test-config'
                                writeFile file: configFile, text: """features:
  secrets:
    externalSecrets:
      vault:
        server: \"${vaultServer}\"
"""
                                return configFile
                            }

                            profiles.each { profile ->
                                withK3dCluster(profile) {
                                    def externalVaultContainer = null
                                    try {
                                        def additionalArguments = ''

                                        if (profile == 'full-secrets') {
                                            docker.image("${env.GOLANG_IMAGE}").inside(env.INTEGRATION_TEST_DOCKER_ARGS) {
                                                sh '''
                                                    apk add --no-cache kubectl
                                                    kubectl create namespace gop-job --dry-run=client -o yaml | kubectl apply -f -
                                                    kubectl apply -f ./scripts/dev/secrets/gop-secrets.yaml
                                                '''
                                            }
                                        }

                                        if (profile == 'full-external-vault' || profile == 'operator-full-external-vault') {
                                            externalVaultContainer = "external-vault-${env.BUILD_ID}"
                                            def externalVaultServer
                                            docker.image("${env.GOLANG_IMAGE}").inside(env.INTEGRATION_TEST_DOCKER_ARGS) {
                                                sh 'apk add --no-cache bash kubectl docker-cli'
                                                externalVaultServer = sh(
                                                    script: "./scripts/dev/external-vault/prepare-test-vault.sh --container-name=${externalVaultContainer} --host-port=0",
                                                    returnStdout: true
                                                ).trim()
                                            }
                                            additionalArguments = " --config-file=${createExternalVaultIntegrationConfig(externalVaultServer)}"
                                        }

                                        if (profile.startsWith('operator')) {
                                            docker.image("${env.GOLANG_IMAGE}").inside(env.INTEGRATION_TEST_DOCKER_ARGS) {
                                                sh 'apk add --no-cache make bash curl git kubectl && make install-operator'
                                            }
                                        }

                                        if (profile == 'full-netpols') {
                                            additionalArguments = " --config-file=${createNetworkPolicyIntegrationConfig()} -x"
                                        }

                                        docker.image("${env.FULL_IMAGE_TAG}").inside(env.INTEGRATION_TEST_DOCKER_ARGS) {
                                            sh "java -jar /app/gitops-playground.jar --profile=${profile}${additionalArguments}"
                                        }
                                        docker.image("${env.MAVEN_IMAGE}").inside(env.INTEGRATION_TEST_DOCKER_ARGS) {
                                            try {
                                                sh "mvn -B failsafe:integration-test failsafe:verify -Dmicronaut.environments=${profile} -Dsurefire.reportNameSuffix=${profile}"
                                            } finally {
                                                sh '[ ! -e target ] || chown -R $BUILD_USER:$BUILD_GROUP target'
                                            }
                                        }
                                    } finally {
                                        if (externalVaultContainer != null) {
                                            sh "docker rm -f ${externalVaultContainer} >/dev/null 2>&1 || true"
                                        }
                                    }
                                }

                            }
                        }
                    }
                    post {
                        always {
                            junit testResults: "**/target/failsafe-reports/TEST-*.xml",
                                    allowEmptyResults: true
                        }
                    }
                }
            }
        }

        stage('Create Release Tag') {
            when {
                expression { return params.performRelease }
            }
            steps {
                script {
                    withCredentials([usernamePassword(
                            credentialsId: 'cesmarvin',
                            usernameVariable: 'SCM_USERNAME',
                            passwordVariable: 'SCM_TOKEN'
                    )]) {

                        // The key is stored with literal "\n" sequences, printf '%b' expands them
                        withCredentials([string(credentialsId: 'cesmarvin_signing_gpg_privkey', variable: 'GPG_KEY')]) {
                            sh '''
                                export GNUPGHOME="$(mktemp -d)"
                                trap 'gpgconf --kill gpg-agent; rm -rf "$GNUPGHOME"' EXIT
                                printf '%b' "$GPG_KEY" | gpg --batch --import
                                KEY_ID=$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/ {print $5; exit}')
                                # Tagger identity must match the key's UID, taken from the key itself
                                UID_STR=$(gpg --list-secret-keys --with-colons "$KEY_ID" | awk -F: '/^uid/ {print $10; exit}')
                                export GIT_COMMITTER_NAME="${UID_STR% <*}"
                                export GIT_COMMITTER_EMAIL="${UID_STR##*<}"; GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL%>}"
                                git -c user.signingkey="$KEY_ID" tag -s "$TAG_NAME" -m "$TAG_NAME"
                                git -c credential.helper='!f() { echo username=$SCM_USERNAME; echo password=$SCM_TOKEN; }; f' push origin "$TAG_NAME"
                            '''
                        }
                    }
                    withCredentials([usernamePassword(
                            credentialsId: 'cesmarvin-ghcr',
                            usernameVariable: 'GH_USERNAME',
                            passwordVariable: 'GH_TOKEN'
                    )]) {
                        // Create github release and generate release notes. Later further github interactions in Publish Sbom Stage
                        docker.image(env.GOLANG_IMAGE).inside("--entrypoint '' -u 0:0") {
                            sh '''
                                apk add --no-cache github-cli
                                gh release create "$TAG_NAME" \
                                    --repo "$GITHUB_REPOSITORY" \
                                    --title "$TAG_NAME" \
                                    --generate-notes
                            '''
                        }
                    }
                    // Shows the new tag as latest on the next 'Build with Parameters' screen.
                    refreshParameters(env.TAG_NAME)
                }
            }
        }

        stage('Push Image') {
            when {
                anyOf {
                    branch 'main'
                    branch 'develop'
                    buildingTag()
                    expression { return env.TAG_NAME }
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

                        if (env.TAG_NAME) {
                            image.push('latest')
                            image.push(env.TAG_NAME)
                            currentBuild.description += "\nImage: ${env.DOCKER_REGISTRY_BASE_URL}/${env.DOCKER_IMAGE_NAME}:latest"
                            currentBuild.description += "\nRelease: ${env.TAG_NAME}"
                        }
                    }
                }
            }
        }

        stage('Publish SBOM') {
            when {
                expression { return env.TAG_NAME }
            }
            steps {
                script {
                    withCredentials([usernamePassword(
                            credentialsId: 'cesmarvin-ghcr',
                            usernameVariable: 'GH_USERNAME',
                            passwordVariable: 'GH_TOKEN'
                    )]) {
                        docker.image(env.GOLANG_IMAGE).inside("--entrypoint '' -u 0:0") {
                            sh '''
                                apk add --no-cache github-cli
                                asset="gitops-playground-${TAG_NAME}.sbom.cdx.json"
                                cp sbom.cdx.json "$asset"
                                sha256sum "$asset" > "${asset}.sha256"
                                
                                # Upload to the released version 
                                gh release upload "$TAG_NAME" "$asset" "${asset}.sha256" \
                                    --repo "$GITHUB_REPOSITORY" \
                                    --clobber

                                gh release view "$TAG_NAME" --repo "$GITHUB_REPOSITORY" --json body -q .body > notes.md
                                cat >> notes.md <<EOF

## SBOM
- [$asset](https://github.com/$GITHUB_REPOSITORY/releases/download/$TAG_NAME/$asset)
- [${asset}.sha256](https://github.com/$GITHUB_REPOSITORY/releases/download/$TAG_NAME/${asset}.sha256)
EOF
                                gh release edit "$TAG_NAME" --repo "$GITHUB_REPOSITORY" --notes-file notes.md
                            '''
                        }
                        sh '''
                            printf '%s' "$GH_TOKEN" | docker run --rm -i \
                                -v "$WORKSPACE:/workspace" \
                                -w /workspace \
                                "$ORAS_IMAGE" attach \
                                    --username "$GH_USERNAME" \
                                    --password-stdin \
                                    --artifact-type application/vnd.cyclonedx+json \
                                    "$DOCKER_REGISTRY_BASE_URL/$DOCKER_IMAGE_NAME:$TAG_NAME" \
                                    "gitops-playground-${TAG_NAME}.sbom.cdx.json:application/vnd.cyclonedx+json" \
                                    "gitops-playground-${TAG_NAME}.sbom.cdx.json.sha256:text/plain"
                        '''
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                if (isTriggeredByTimer()) {
                    currentBuild.displayName = "#${env.BUILD_NUMBER} weekly"
                    emailext(
                            subject: "Weekly build ${currentBuild.currentResult}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                            body: '${SCRIPT, template="groovy-html.template"}',
                            mimeType: 'text/html',
                            to: env.GOP_DEVELOPERS
                    )
                }
            }
        }
        changed {
            script {
                if (!isTriggeredByTimer()) {
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
    }
}

boolean isTriggeredByTimer() {
    return !currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').isEmpty()
}

boolean isTriggeredByUser() {
    return !currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').isEmpty()
}

String nextPatchVersion(String latestTag) {
    if (!latestTag) {
        return '0.0.1'
    }
    def parts = latestTag.tokenize('.').collect { it.toInteger() }
    return "${parts[0]}.${parts[1]}.${parts[2] + 1}"
}

// Refreshes the parameter descriptions shown on the next 'Build with Parameters' screen.
void refreshParameters(String latestTag) {
    properties([parameters([
            booleanParam(defaultValue: false, name: 'forcePushImage', description: 'Pushes the image with the current git commit as tag, even when it is on a branch'),
            booleanParam(defaultValue: false, name: 'noCache', description: 'Builds the docker image without cache'),
            choice(name: 'chooseProfile', choices: ['full', 'minimal', 'all-profiles', 'full-prefix', 'content-examples', 'operator-full', 'operator-mandants'], description: 'Starts GOP with given profile only and execute tests which belongs to profile.'),
            booleanParam(defaultValue: false, name: 'performRelease', description: 'Creates a GitHub Release: tags HEAD, publishes SBOM assets to the release and pushes the image to GHCR.'),
            string(defaultValue: '', name: 'releaseVersion', description: "Version to release, schema x.y.z. Leave empty to use the next patch version after the latest remote tag (currently ${latestTag ?: 'none'}). Only used when performRelease is checked."),
            booleanParam(defaultValue: false, name: 'skipIntegrationTests', description: 'Skips the integration tests stage')
    ])])
}