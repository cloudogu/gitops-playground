#!groovy

String getApplication() { 'spring-petclinic-plain' }
String getConfigRepositoryPRRepo() { '${namePrefix}argocd/example-apps' }
String getScmManagerCredentials() { 'scmm-user' }
String getConfigRepositoryPRBaseUrl() { env.SCMM_URL }
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
String getCesBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/ces-build-lib" }
String getCesBuildLibVersion() { '2.2.0' }
String getGitOpsBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/gitops-build-lib" }
String getGitOpsBuildLibVersion() { '0.4.0'}

loadLibraries()

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
            // Tests skipped for faster demo and exercise purposes
            //mvn 'test -Dmaven.test.failure.ignore=true -Dcheckstyle.skip'
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
            if (isBuildSuccessful() && env.BRANCH_NAME in ['main']) {

                def gitopsConfig = [
                        scm: [
                                provider     : 'SCMManager',
                                credentialsId: scmManagerCredentials,
                                baseUrl      : configRepositoryPRBaseUrl,
                                repositoryUrl   : configRepositoryPRRepo,
                        ],
                        application: application,
                        gitopsTool: 'ARGO',
                        folderStructureStrategy: 'ENV_PER_APP',
</#noparse>
                        k8sVersion : env.${namePrefixForEnvVars}K8S_VERSION,
                        deployments: [
                                sourcePath: 'k8s',
                                destinationRootPath: 'apps',
                                plain: [
                                        updateImages: [
                                                [ filename: 'deployment.yaml',
                                                  containerName: application,
                                                  imageName: imageName ]
                                        ]
                                ]
                        ],
                        fileConfigmaps: [
                                // Showcase for gitops-build-lib: Convert file into a config map
                                [
                                        name : 'messages',
                                        sourceFilePath : '../src/main/resources/messages/messages.properties',
                                        stage: ['staging', 'production']
                                ]
                        ],
                        stages: [
                                staging: [
                                        namespace: '${namePrefix}example-apps-staging',
                                        deployDirectly: true ],
                                production: [
                                        namespace: '${namePrefix}example-apps-production',
                                        deployDirectly: false ],
                        ]
                ]
<#noparse>
                addSpecificGitOpsConfig(gitopsConfig)
                
                deployViaGitops(gitopsConfig)
            } else {
                echo 'Skipping deploy, because build not successful or not on main branch'
            }
        }
    }

    // Archive Unit and integration test results, if any
    junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/TEST-*.xml,**/target/surefire-reports/TEST-*.xml'
}

/** Initializations might not be needed in a real-world setup, but are necessary for GitOps playground */
void addSpecificGitOpsConfig(gitopsConfig) {
    gitopsConfig += [
        // In the GitOps playground, we're loading the build libs from our local SCM so it also works in an offline context
        // As the gitops-build-lib also uses the ces-build-lib we need to pass those parameters on.
        // If you can access the internet, you can rely on the defaults, which load the lib from GitHub.
        cesBuildLibRepo: cesBuildLibRepo,
        cesBuildLibVersion: cesBuildLibVersion,
        cesBuildLibCredentialsId: scmManagerCredentials,
        
        
        // The GitOps playground provides parameters for overwriting the build images used by gitops-build-lib, so 
        // it also works in an offline context.
        // Those parameters overwrite the following parameters.
        // If you can access the internet, you can rely on the defaults, which load the images from public registries.
        buildImages          : [
</#noparse>
            helm: '${images.helm}',
            kubectl: '${images.kubectl}',
            kubeval: '${images.kubeval}',
            helmKubeval: '${images.helmKubeval}',
            yamllint: '${images.yamllint}'
<#noparse>
        ]
    ]
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

def loadLibraries() {
    // In the GitOps playground, we're loading the build libs from our local SCM so it also works in an offline context
    // If you can access the internet, you could also load the libraries directly from github like so
    // @Library(["github.com/cloudogu/ces-build-lib@${cesBuildLibVersion}", "github.com/cloudogu/gitops-build-lib@${gitOpsBuildLibRepo}"]) _
    //import com.cloudogu.ces.cesbuildlib.*
    //import com.cloudogu.ces.gitopsbuildlib.*
    
    cesBuildLib = library(identifier: "ces-build-lib@${cesBuildLibVersion}",
            retriever: modernSCM([$class: 'GitSCMSource', remote: cesBuildLibRepo, credentialsId: scmManagerCredentials])
    ).com.cloudogu.ces.cesbuildlib

    library(identifier: "gitops-build-lib@${gitOpsBuildLibVersion}",
            retriever: modernSCM([$class: 'GitSCMSource', remote: gitOpsBuildLibRepo, credentialsId: scmManagerCredentials])
    ).com.cloudogu.gitops.gitopsbuildlib
}

def cesBuildLib
def gitOpsBuildLib
</#noparse>
