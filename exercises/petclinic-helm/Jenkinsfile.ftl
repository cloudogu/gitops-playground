#!groovy

String getApplication() { "exercise-spring-petclinic-helm" }
String getScmManagerCredentials() { 'scmm-user' }
String getConfigRepositoryPRBaseUrl() { env.SCMM_URL }
String getConfigRepositoryPRRepo() { '${namePrefix}argocd/example-apps' }

String getDockerRegistryBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_URL }
String getDockerRegistryPath() { env.${namePrefixForEnvVars}REGISTRY_PATH }
String getDockerRegistryCredentials() { 'registry-user' }

<#if registry.twoRegistries>
String getDockerRegistryProxyCredentials() { 'registry-proxy-user' }
String getDockerRegistryProxyBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_PROXY_URL }
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
<#noparse>
            String pathPrefix = !dockerRegistryPath?.trim() ? "" : "${dockerRegistryPath}/"
            imageName = "${dockerRegistryBaseUrl}/${pathPrefix}${application}:${imageTag}"
</#noparse>
<#if registry.twoRegistries>
<#noparse>
            docker.withRegistry("http://${dockerRegistryProxyBaseUrl}", dockerRegistryProxyCredentials) {
                image = docker.build(imageName, '.')
            }
</#noparse>
<#else>
<#noparse>
            image = docker.build(imageName, '.')
</#noparse>
</#if>
<#noparse>
                if (isBuildSuccessful()) {
                    docker.withRegistry("http://${dockerRegistryBaseUrl}", dockerRegistryCredentials) {
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
