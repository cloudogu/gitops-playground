#!groovy

String getApplication() { "spring-petclinic-helm" }
String getScmManagerCredentials() { 'scmm-user' }
String getConfigRepositoryPRBaseUrl() { env.SCMM_URL }
String getConfigRepositoryPRRepo() { '${namePrefix}argocd/example-apps' }

String getDockerRegistryBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_URL }
String getDockerRegistryPath() { env.${namePrefixForEnvVars}REGISTRY_PATH }
String getDockerRegistryCredentials() { 'registry-user' }

<#if registry.twoRegistries>
String getDockerRegistryProxyBaseUrl() { env.${namePrefixForEnvVars}REGISTRY_PROXY_URL }
String getDockerRegistryProxyCredentials() { 'registry-proxy-user' }
</#if>

<#noparse>
String getCesBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/ces-build-lib/" }
String getCesBuildLibVersion() { '2.5.0' }
String getGitOpsBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/gitops-build-lib" }
String getGitOpsBuildLibVersion() { '0.7.0'}
String getHelmChartRepository() { "${env.SCMM_URL}/repo/3rd-party-dependencies/spring-boot-helm-chart-with-dependency" }
String getHelmChartVersion() { "1.0.0" }
String getMainBranch() { 'main' }

cesBuildLib = library(identifier: "ces-build-lib@${cesBuildLibVersion}",
        retriever: modernSCM([$class: 'GitSCMSource', remote: cesBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.ces.cesbuildlib

gitOpsBuildLib = library(identifier: "gitops-build-lib@${gitOpsBuildLibVersion}",
    retriever: modernSCM([$class: 'GitSCMSource', remote: gitOpsBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.gitops.gitopsbuildlib

properties([
        // Don't run concurrent builds, because the ITs use the same port causing random failures on concurrent builds.
        disableConcurrentBuilds()
])

node {

</#noparse>
<#if images.maven?has_content>
  <#if registry.twoRegistries>
      mvn = cesBuildLib.MavenInDocker.new(this, '${images.maven}', dockerRegistryProxyCredentials)
  <#else>
      mvn = cesBuildLib.MavenInDocker.new(this, '${images.maven}')
  </#if>
<#else>
    mvn = cesBuildLib.MavenWrapper.new(this)
</#if>

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
            docker.withRegistry("https://${dockerRegistryProxyBaseUrl}", dockerRegistryProxyCredentials) {
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
                docker.withRegistry("https://${dockerRegistryBaseUrl}", dockerRegistryCredentials) {
                    image.push()
                }
            } else {
                echo 'Skipping docker push, because build not successful'
            }
        }

        stage('Deploy') {
            if (isBuildSuccessful() && env.BRANCH_NAME in [mainBranch]) {

                def gitopsConfig = [
                        scm                     : [
                                provider     : 'SCMManager',
                                credentialsId: scmManagerCredentials,
                                baseUrl      : configRepositoryPRBaseUrl,
                                repositoryUrl   : configRepositoryPRRepo,
                        ],
                        cesBuildLibRepo: cesBuildLibRepo,
                        cesBuildLibVersion: cesBuildLibVersion,
                        cesBuildLibCredentialsId: scmManagerCredentials,
                        application: application,
                        mainBranch: mainBranch,
                        gitopsTool: 'ARGO',
                        folderStructureStrategy: 'ENV_PER_APP',
</#noparse>
                        k8sVersion : env.${namePrefixForEnvVars}K8S_VERSION,
                        buildImages          : [
<#if registry.twoRegistries>
                                helm:       [
                                        image: '${images.helm}',
                                        credentialsId: dockerRegistryProxyCredentials
                                ],
                                kubectl:    [
                                        image: '${images.kubectl}',
                                        credentialsId: dockerRegistryProxyCredentials
                                ],
                                kubeval:    [
                                        image: '${images.kubeval}',
                                        credentialsId: dockerRegistryProxyCredentials
                                ],
                                helmKubeval: [
                                        image: '${images.helmKubeval}',
                                        credentialsId: dockerRegistryProxyCredentials
                                ],
                                yamllint:   [
                                        image: '${images.yamllint}',
                                        credentialsId: dockerRegistryProxyCredentials
                                ]
<#else>
                                helm: '${images.helm}',
                                kubectl: '${images.kubectl}',
                                kubeval: '${images.kubeval}',
                                helmKubeval: '${images.helmKubeval}',
                                yamllint: '${images.yamllint}'
</#if>
                        ],
                        deployments: [
                            sourcePath: 'k8s',
                            destinationRootPath: 'apps',
                            helm : [
                                repoType : 'GIT',
                                credentialsId : scmManagerCredentials,
                                repoUrl  : helmChartRepository,
                                version: helmChartVersion,
                                updateValues  : [[fieldPath: "image.name", newValue: imageName]]
                            ]
                        ],
                        stages: [
                                staging: [
                                        namespace: '${namePrefix}example-apps-staging',
                                        deployDirectly: true ],
                                production: [
                                        namespace: '${namePrefix}example-apps-production',
                                        deployDirectly: false ]
                        ]
                ]
<#noparse>
                deployViaGitops(gitopsConfig)
            } else {
                echo 'Skipping deploy, because build not successful or not on main branch'
            }
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
def gitOpsBuildLib
</#noparse>
