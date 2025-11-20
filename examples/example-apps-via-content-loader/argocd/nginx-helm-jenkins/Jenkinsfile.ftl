#!groovy

String getApplication() { "nginx-helm-jenkins" }
String getScmManagerCredentials() { 'scm-user' }
String getConfigRepositoryPRBaseUrl() { env.${config.application.namePrefixForEnvVars}SCM_URL}
String getConfigRepositoryPRPrefixedUrl() { env.${config.application.namePrefixForEnvVars}PREFIXED_SCM_URL}
String getConfigRepositoryPRRepo() { '${config.application.namePrefix}argocd/example-apps' }
<#noparse>

String getCesBuildLibRepo() { configRepositoryPRPrefixedUrl+"3rd-party-dependencies/ces-build-lib/" }
String getGitOpsBuildLibRepo() { configRepositoryPRPrefixedUrl+"3rd-party-dependencies/gitops-build-lib" }

String getCesBuildLibVersion() { '2.5.0' }
String getGitOpsBuildLibVersion() { '0.8.0'}

String getHelmChartRepository() { "https://raw.githubusercontent.com/bitnami/charts/archive-full-index/bitnami" }
String getHelmChartName() { "nginx" }
String getHelmChartVersion() { "13.2.21" }
String getMainBranch() { 'main' }

cesBuildLib = library(identifier: "ces-build-lib@${cesBuildLibVersion}",
        retriever: modernSCM([$class: 'GitSCMSource', remote: cesBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.ces.cesbuildlib

gitOpsBuildLib = library(identifier: "gitops-build-lib@${gitOpsBuildLibVersion}",
    retriever: modernSCM([$class: 'GitSCMSource', remote: gitOpsBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.gitops.gitopsbuildlib

properties([
        // Keep only the last 10 build to preserve space
        disableConcurrentBuilds()
])

node('docker') {

    def git = cesBuildLib.Git.new(this)

    catchError {

        stage('Checkout') {
            checkout scm
            git.clean('')
        }

        stage('Deploy') {
            if (env.BRANCH_NAME in [mainBranch]) {
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
                    k8sVersion : env.${config.application.namePrefixForEnvVars}K8S_VERSION,
                    buildImages          : [
                            helm: '${config.content.variables.images.helm}',
                            kubectl: '${config.content.variables.images.kubectl}',
                            kubeval: '${config.content.variables.images.kubeval}',
                            helmKubeval: '${config.content.variables.images.helmKubeval}',
                            yamllint: '${config.content.variables.images.yamllint}'
                    ],
                    deployments: [
                        sourcePath: 'k8s',
                        destinationRootPath: 'apps',
                        helm : [
                            repoType : 'HELM',
                            repoUrl  : helmChartRepository,
                            chartName: helmChartName,
                            version  : helmChartVersion,
                        ]
                    ],
                    stages: [
                            staging: [
                                namespace: '${config.application.namePrefix}example-apps-staging',
                                deployDirectly: true
                                ],
                            production: [
                                namespace: '${config.application.namePrefix}example-apps-production',
                                deployDirectly: false
                                ],
                    ],
<#noparse>
                    fileConfigmaps: [
                            [
                                name : "index-nginx",
                                sourceFilePath : "../index.html",
                                stage: ["staging", "production"]
                            ]
                    ]
                ]

            deployViaGitops(gitopsConfig)
            } else {
                echo 'Skipping deploy, because build not successful or not on main branch'

            }
        }
    }
}

def cesBuildLib
def gitOpsBuildLib
</#noparse>