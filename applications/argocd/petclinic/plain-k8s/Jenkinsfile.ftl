#!groovy

String getApplication() { 'spring-petclinic-plain' }
String getConfigRepositoryPRRepo() { '${config.application.namePrefix}argocd/example-apps' }
String getScmManagerCredentials() { 'scmm-user' }
String getConfigRepositoryPRBaseUrl() { env.${config.application.namePrefixForEnvVars}SCMM_URL }

String getDockerRegistryBaseUrl() { env.${config.application.namePrefixForEnvVars}REGISTRY_URL }
String getDockerRegistryPath() { env.${config.application.namePrefixForEnvVars}REGISTRY_PATH }
String getDockerRegistryCredentials() { 'registry-user' }

<#if config.registry.twoRegistries>
String getDockerRegistryProxyBaseUrl() { env.${config.application.namePrefixForEnvVars}REGISTRY_PROXY_URL }
String getDockerRegistryProxyCredentials() { 'registry-proxy-user' }
</#if>

<#noparse>

String getCesBuildLibRepo() { configRepositoryPRBaseUrl+"/repo/3rd-party-dependencies/ces-build-lib/" }
String getGitOpsBuildLibRepo() { configRepositoryPRBaseUrl+"/repo/3rd-party-dependencies/gitops-build-lib" }

String getCesBuildLibVersion() { '2.5.0' }
String getGitOpsBuildLibVersion() { '0.7.0'}

loadLibraries()

properties([
        // Don't run concurrent builds, because the ITs use the same port causing random failures on concurrent builds.
        disableConcurrentBuilds()
])

node {

</#noparse>

<#if config.images.maven?has_content>
    <#if config.registry.twoRegistries>
        mvn = cesBuildLib.MavenInDocker.new(this, '${config.images.maven}', dockerRegistryProxyCredentials)
    <#else>
        mvn = cesBuildLib.MavenInDocker.new(this, '${config.images.maven}')
    </#if>
<#else>
    mvn = cesBuildLib.MavenWrapper.new(this)
</#if>

<#if config.jenkins.mavenCentralMirror?has_content>
    mvn.useMirrors([name: 'maven-central-mirror', mirrorOf: 'central', url:  env.${config.application.namePrefixForEnvVars}MAVEN_CENTRAL_MIRROR])
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
            // Disable database integration tests because they start docker images (which won't work in air-gapped envs and take a lot of time in demos)
            mvn 'test -Dmaven.test.failure.ignore=true -Dcheckstyle.skip ' +
            '-Dtest=!org.springframework.samples.petclinic.MySqlIntegrationTests,!org.springframework.samples.petclinic.PostgresIntegrationTests'
        }

        String imageName = ""
        stage('Docker') {
            String imageTag = createImageTag()
</#noparse>
<#noparse>
            String pathPrefix = !dockerRegistryPath?.trim() ? "" : "${dockerRegistryPath}/"
            imageName = "${dockerRegistryBaseUrl}/${pathPrefix}${application}:${imageTag}"
</#noparse>
<#if config.registry.twoRegistries>
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
                        k8sVersion : env.${config.application.namePrefixForEnvVars}K8S_VERSION,
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
                                        namespace: '${config.application.namePrefix}example-apps-staging',
                                        deployDirectly: true ],
                                production: [
                                        namespace: '${config.application.namePrefix}example-apps-production',
                                        deployDirectly: false ],
                        ]
                ]
<#noparse>
                gitopsConfig += createSpecificGitOpsConfig()

                deployViaGitops(gitopsConfig)
            } else {
                echo 'Skipping deploy, because build not successful or not on main branch'
            }
        }
    }

    // Archive Unit and integration test results, if any
    junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/TEST-*.xml,**/target/surefire-reports/TEST-*.xml'
}

/** Initializations might not be needed in a real-world setup, but are necessary to work in an air-gapped env, for example */
String createSpecificGitOpsConfig() {
    [
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
<#if config.registry.twoRegistries>
            helm:       [
                     image: '${config.images.helm}',
                     credentialsId: dockerRegistryProxyCredentials
            ],
            kubectl:    [
                    image: '${config.images.kubectl}',
                    credentialsId: dockerRegistryProxyCredentials
            ],
            kubeval:    [
                    image: '${config.images.kubeval}',
                    credentialsId: dockerRegistryProxyCredentials
            ],
            helmKubeval: [
                    image: '${config.images.helmKubeval}',
                    credentialsId: dockerRegistryProxyCredentials
            ],
            yamllint:   [
                    image: '${config.images.yamllint}',
                    credentialsId: dockerRegistryProxyCredentials
            ]
<#else>
            helm: '${config.images.helm}',
            kubectl: '${config.images.kubectl}',
            kubeval: '${config.images.kubeval}',
            helmKubeval: '${config.images.helmKubeval}',
            yamllint: '${config.images.yamllint}'
</#if>
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