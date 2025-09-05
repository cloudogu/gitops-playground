package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.gitlab4j.api.GitLabApi
import java.util.logging.Level

@Slf4j
@Singleton
@Order(60)
class ScmmManager extends Feature {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"

    String namespace
    private Config config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private K8sClient k8sClient
    private NetworkingUtils networkingUtils
    String centralSCMUrl

    ScmmManager(
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
        this.centralSCMUrl = config.multiTenant.centralScmUrl

        if(config.scmm.internal) {
            this.namespace = "${config.application.namePrefix}scm-manager"
        }
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {
        if (config.multiTenant.useDedicatedInstance) {
            this.centralSCMUrl = !config.multiTenant.internal ? config.multiTenant.centralScmUrl : "http://scmm.scm-manager.svc.cluster.local/scm"
        }

        if (config.scmm.internal) {
            String releaseName = 'scmm'

            k8sClient.createNamespace(namespace)

            def helmConfig = config.scmm.helm

            def templatedMap = templateToMap(HELM_VALUES_PATH, [
                    host       : config.scmm.ingress,
                    remote     : config.application.remote,
                    username   : config.scmm.username,
                    password   : config.scmm.password,
                    helm       : config.scmm.helm,
                    releaseName: releaseName
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
                config.scmm.url = networkingUtils.createUrl("${releaseName}.${namespace}.svc.cluster.local", "80", contentPath)
            } else {
                log.debug("Setting internal configs for local single node cluster with internal scmm. Waiting for NodePort...")
                def port = k8sClient.waitForNodePort(releaseName, namespace)
                String clusterBindAddress = networkingUtils.findClusterBindAddress()
                config.scmm.url = networkingUtils.createUrl(clusterBindAddress, port, contentPath)

                if (config.multiTenant.useDedicatedInstance && config.multiTenant) {
                    log.debug("Setting internal configs for local single node cluster with internal central scmm. Waiting for NodePort...")
                    def portCentralScm = k8sClient.waitForNodePort(releaseName, "scm-manager")
                    centralSCMUrl = networkingUtils.createUrl(clusterBindAddress, portCentralScm, contentPath)
                }
            }
        }

        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [

                GIT_COMMITTER_NAME           : config.application.gitName,
                GIT_COMMITTER_EMAIL          : config.application.gitEmail,
                GIT_AUTHOR_NAME              : config.application.gitName,
                GIT_AUTHOR_EMAIL             : config.application.gitEmail,
                GITOPS_USERNAME              : config.scmm.gitOpsUsername,
                TRACE                        : config.application.trace,
                SCMM_URL                     : config.scm.getScmmConfig().url,
                SCMM_USERNAME                : config.scm.getScmmConfig(),
                SCMM_PASSWORD                : config.scm.getScmmConfig(),
                JENKINS_URL                  : config.jenkins.url,
                INTERNAL_SCMM                : config.scm.internal,
                JENKINS_URL_FOR_SCMM         : config.jenkins.urlForScmm,
                SCMM_URL_FOR_JENKINS         : config.scmm.urlForJenkins,
                // Used indirectly in utils.sh 😬
                REMOTE_CLUSTER               : config.application.remote,
                INSTALL_ARGOCD               : config.features.argocd.active,
                SPRING_BOOT_HELM_CHART_COMMIT: config.repositories.springBootHelmChart.ref,
                SPRING_BOOT_HELM_CHART_REPO  : config.repositories.springBootHelmChart.url,
                GITOPS_BUILD_LIB_REPO        : config.repositories.gitopsBuildLib.url,
                CES_BUILD_LIB_REPO           : config.repositories.cesBuildLib.url,
                NAME_PREFIX                  : config.application.namePrefix,
                INSECURE                     : config.application.insecure,
                SCM_ROOT_PATH                : config.scm.scmmConfig.rootPath,
                SCM_PROVIDER                 : 'scm-manager',
                CONTENT_EXAMPLES             : config.content.examples,
                SKIP_RESTART                 : config.scm.scmmConfig.skipRestart,
                SKIP_PLUGINS                 : config.scm.scmmConfig.skipPlugins,
                CENTRAL_SCM_URL              : centralSCMUrl,
                CENTRAL_SCM_USERNAME         : config.multiTenant.scmmConfig.username,
                CENTRAL_SCM_PASSWORD         : config.multiTenant.scmmConfig.password
        ])
    }
}