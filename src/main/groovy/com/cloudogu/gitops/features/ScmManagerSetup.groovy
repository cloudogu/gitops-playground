package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(50)
class ScmManagerSetup extends Feature {

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/scm-manager/misc/values.ftl.yaml"

    String namespace
    private Config config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private K8sClient k8sClient
    private NetworkingUtils networkingUtils
    String centralSCMUrl

    ScmManagerSetup(
            Config config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer,
            K8sClient k8sClient,
            NetworkingUtils networkingUtils
    ) {
        this.config = config
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.deployer = deployer
        this.k8sClient = k8sClient
        this.networkingUtils = networkingUtils

        if (config.scm.internal) {
            this.namespace = "${config.application.namePrefix}scm-manager"
        }
    }

    @Override
    boolean isEnabled() {
        return config.scm.scmProviderType == ScmProviderType.SCM_MANAGER
//        false
    }

    @Override
    void enable() {
        if (config.scm.scmManager.internal) {
            String releaseName = config.scm.scmManager.releaseName

            k8sClient.createNamespace(namespace)

            def helmConfig = config.scm.scmManager.helm

            def templatedMap = templateToMap(HELM_VALUES_PATH, [
                    config     : config
            ])

            def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
            def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

            deployer.deployFeature(
                    helmConfig.repoURL,
                    'scm-manager',
                    helmConfig.chart,
                    helmConfig.version,
                    namespace,
                    'scmm',
                    tempValuesPath
            )

            // Update scmm.url after it is deployed (and ports are known)
            // Defined here: https://github.com/scm-manager/scm-manager/blob/3.2.1/scm-packaging/helm/src/main/chart/templates/_helpers.tpl#L14-L25
            String contentPath = "/scm"


            if (config.application.runningInsideK8s) {
                log.debug("Setting scmm url to k8s service, since installation is running inside k8s")
                config.scm.scmManager.url = networkingUtils.createUrl("${releaseName}.${namespace}.svc.cluster.local", "80", contentPath)
            } else {
                log.debug("Setting internal configs for local single node cluster with internal scmm. Waiting for NodePort...")
                def port = k8sClient.waitForNodePort(releaseName, namespace)
                String clusterBindAddress = networkingUtils.findClusterBindAddress()
                config.scm.scmManager.url = networkingUtils.createUrl(clusterBindAddress, port, contentPath)

                if (config.multiTenant.useDedicatedInstance && config.multiTenant.scmProviderType == ScmProviderType.SCM_MANAGER) {
                    log.debug("Setting internal configs for local single node cluster with internal central scmm. Waiting for NodePort...")
                    def portCentralScm = k8sClient.waitForNodePort(releaseName, config.multiTenant.scmManager.namespace)
                    centralSCMUrl = networkingUtils.createUrl(clusterBindAddress, portCentralScm, contentPath)
                }
            }
        }

        //disable setup for faster testing
        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [

                GIT_COMMITTER_NAME           : config.application.gitName,
                GIT_COMMITTER_EMAIL          : config.application.gitEmail,
                GIT_AUTHOR_NAME              : config.application.gitName,
                GIT_AUTHOR_EMAIL             : config.application.gitEmail,
                GITOPS_USERNAME              : config.scm.scmManager.gitOpsUsername,
                TRACE                        : config.application.trace,
                SCMM_URL                     : config.scm.scmManager.url,
                SCMM_USERNAME                : config.scm.scmManager.username,
                SCMM_PASSWORD                : config.scm.scmManager.password,
                JENKINS_URL                  : config.jenkins.url,
                INTERNAL_SCMM                : config.scm.scmManager.internal,
                JENKINS_URL_FOR_SCMM         : config.jenkins.urlForScm,
                SCMM_URL_FOR_JENKINS         : config.scm.scmManager.urlForJenkins,
                // Used indirectly in utils.sh 😬
                REMOTE_CLUSTER               : config.application.remote,
                INSTALL_ARGOCD               : config.features.argocd.active,
                NAME_PREFIX                  : config.application.namePrefix,
                INSECURE                     : config.application.insecure,
                SCM_ROOT_PATH                : config.scm.scmManager.rootPath,
                SCM_PROVIDER                 : 'scm-manager',
                CONTENT_EXAMPLES             : false,
                SKIP_RESTART                 : config.scm.scmManager.skipRestart,
                SKIP_PLUGINS                 : config.scm.scmManager.skipPlugins,
                CENTRAL_SCM_URL              : config.multiTenant.scmManager.url,
                CENTRAL_SCM_USERNAME         : config.multiTenant.scmManager.username,
                CENTRAL_SCM_PASSWORD         : config.multiTenant.scmManager.password
        ])
    }
}