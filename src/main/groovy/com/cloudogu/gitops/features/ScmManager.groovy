package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(80)
class ScmManager extends Feature {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"

    private Map config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer

    ScmManager(
            Configuration config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer
    ) {
        this.config = config.getConfig()
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.deployer = deployer
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {

        if (config.scmm['internal']) {
            def helmConfig = config['scmm']['helm']

            def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [
                    host  : config.scmm['ingress'],
                    remote: config.application['remote'],
                    username:  config.scmm['username'],
                    password: config.scmm['password']
            ]).toPath()

            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'scm-manager',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    'default',
                    'scmm',
                    tmpHelmValues
            )
        }

        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [

                GIT_COMMITTER_NAME           : config.application['gitName'],
                GIT_COMMITTER_EMAIL          : config.application['gitEmail'],
                GIT_AUTHOR_NAME              : config.application['gitName'],
                GIT_AUTHOR_EMAIL             : config.application['gitEmail'],
                GITOPS_USERNAME              : config.scmm['gitOpsUsername'],
                TRACE                        : config.application['trace'],
                SCMM_URL                     : config.scmm['url'],
                SCMM_USERNAME                : config.scmm['username'],
                SCMM_PASSWORD                : config.scmm['password'],
                JENKINS_URL                  : config.jenkins['url'],
                JENKINS_URL_FOR_SCMM         : config.jenkins['urlForScmm'],
                SCMM_URL_FOR_JENKINS         : config.scmm['urlForJenkins'],
                // Used indirectly in utils.sh 😬
                REMOTE_CLUSTER               : config.application['remote'],
                INSTALL_ARGOCD               : config.features['argocd']['active'],
                SPRING_BOOT_HELM_CHART_COMMIT: config.repositories['springBootHelmChart']['ref'],
                SPRING_BOOT_HELM_CHART_REPO  : config.repositories['springBootHelmChart']['url'],
                GITOPS_BUILD_LIB_REPO        : config.repositories['gitopsBuildLib']['url'],
                CES_BUILD_LIB_REPO           : config.repositories['cesBuildLib']['url'],
                NAME_PREFIX                  : config.application['namePrefix'],
                INSECURE                     : config.application['insecure'],
        ])
    }
}
