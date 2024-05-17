#!groovy

String getApplication() { "exercise-spring-petclinic-helm" }
String getScmManagerCredentials() { 'scmm-user' }
String getConfigRepositoryPRBaseUrl() { env.SCMM_URL }
String getConfigRepositoryPRRepo() { '${namePrefix}argocd/example-apps' }
<#if registry.twoRegistries>
String getDockerRegistryPullBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_PULL_URL }
String getDockerRegistryPullPath() { env.${namePrefixForEnvVars}REGISTRY_PULL_PATH }
String getDockerRegistryPullCredentials() { 'registry-pull-user' }
String getDockerRegistryPushBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_PUSH_URL }
String getDockerRegistryPushPath() { env.${namePrefixForEnvVars}REGISTRY_PUSH_PATH }
String getDockerRegistryPushCredentials() { 'registry-push-user' }
<#else>
String getDockerRegistryBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_URL }
String getDockerRegistryPath() { env.${namePrefixForEnvVars}REGISTRY_PATH }
String getDockerRegistryCredentials() { 'registry-user' }
</#if>
<#noparse>
String getCesBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/ces-build-lib/" }
String getCesBuildLibVersion() { '2.2.0' }
String getHelmChartRepository() { "${env.SCMM_URL}/repo/3rd-party-dependencies/spring-boot-helm-chart-with-dependency" }
String getHelmChartVersion() { "1.0.0" }
String getMainBranch() { 'main' }

cesBuildLib = library(identifier: "ces-build-lib@${cesBuildLibVersion}",
        retriever: modernSCM([$class: 'GitSCMSource', remote: cesBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.ces.cesbuildlib

properties([
        // Don't run concurrent builds, because the ITs use the same port causing random failures on concurrent builds.
        disableConcurrentBuilds()
])

node {

    mvn = cesBuildLib.MavenWrapper.new(this)
</#noparse>
<#if jenkins.mavenCentralMirror?has_content>
    mvn.useMirrors([name: 'maven-central-mirror', mirrorOf: 'central', url:  env.${namePrefixForEnvVars}MAVEN_CENTRAL_MIRROR])
</#if>
<#noparse>


    catchError {

        stage('Checkout') {
            checkout scm
        }

        stage('Build') {
            mvn 'clean package -DskipTests -Dcheckstyle.skip'
            archiveArtifacts artifacts: '**/target/*.jar'
        }

        stage('Test') {
            mvn "test -Dmaven.test.failure.ignore=true -Dcheckstyle.skip"
        }

        String imageName = ""
        stage('Docker') {
            String imageTag = createImageTag()
</#noparse>
<#if registry.twoRegistries>
<#noparse>
            String pathPrefix = !dockerRegistryPushPath?.trim() ? "" : "${dockerRegistryPushPath}/"
            imageName = "${dockerRegistryPushBaseUrl}/${pathPrefix}${application}:${imageTag}"
            docker.withRegistry("http://${dockerRegistryPullBaseUrl}", dockerRegistryPullCredentials) {
                image = docker.build(imageName, '.')
            }
</#noparse>
<#else>
<#noparse>
            String pathPrefix = !dockerRegistryPath?.trim() ? "" : "${dockerRegistryPath}/"
            imageName = "${dockerRegistryBaseUrl}/${pathPrefix}${application}:${imageTag}"
            image = docker.build(imageName, '.')
</#noparse>
</#if>


                if (isBuildSuccessful()) {
<#if registry.twoRegistries>
<#noparse>
                            docker.withRegistry("http://${dockerRegistryPushBaseUrl}", dockerRegistryPushCredentials) {
</#noparse>
<#else>
<#noparse>
                docker.withRegistry("http://${dockerRegistryBaseUrl}", dockerRegistryCredentials) {
</#noparse>
</#if>
<#noparse>
                    image.push()
                }
            } else {
                echo 'Skipping docker push, because build not successful'
            }
        }

        stage('Deploy') {
            echo 'Use our gitops-build-lib to deploy this application via helm!'
        }
    }

    // Archive Unit and integration test results, if any
    junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/TEST-*.xml,**/target/surefire-reports/TEST-*.xml'
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
</#noparse>
