#!groovy

String getApplication() { "exercise-nginx-helm" }
String getScmManagerCredentials() { 'scmm-user' }
String getConfigRepositoryPRBaseUrl() { env.SCMM_URL }
String getConfigRepositoryPRRepo() { '${namePrefix}argocd/example-apps' }
<#noparse>
String getCesBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/ces-build-lib/" }
String getCesBuildLibVersion() { '1.64.1' }
String getGitOpsBuildLibRepo() { "${env.SCMM_URL}/repo/3rd-party-dependencies/gitops-build-lib" }
String getGitOpsBuildLibVersion() { '0.4.0'}
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
                    k8sVersion : env.${namePrefixForEnvVars}K8S_VERSION,
                    buildImages          : [
                        helm: '${images.helm}',
                        kubectl: '${images.kubectl}',
                        kubeval: '${images.kubeval}',
                        helmKubeval: '${images.helmKubeval}',
                        yamllint: '${images.yamllint}'
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
                                namespace: '${namePrefix}example-apps-staging',
                                deployDirectly: true
                                ],
                            production: [
                                namespace: '${namePrefix}example-apps-production',
                                deployDirectly: false
                                ],
                    ],
<#noparse>
                    fileConfigmaps: [
                            [
                                name : "exercise-index-nginx",
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
