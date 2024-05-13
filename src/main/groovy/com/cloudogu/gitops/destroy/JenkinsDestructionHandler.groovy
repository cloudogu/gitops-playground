package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JobManager
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Singleton
@Order(300)
class JenkinsDestructionHandler implements DestructionHandler {
    private JobManager jobManager
    private GlobalPropertyManager globalPropertyManager
    private Configuration configuration

    JenkinsDestructionHandler(JobManager jobManager, Configuration configuration, GlobalPropertyManager globalPropertyManager) {
        this.jobManager = jobManager
        this.configuration = configuration
        this.globalPropertyManager = globalPropertyManager
    }

    @Override
    void destroy() {
        jobManager.deleteJob("${configuration.getNamePrefix()}example-apps")
        globalPropertyManager.deleteGlobalProperty("SCMM_URL")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}REGISTRY_URL")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}REGISTRY_PATH")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}REGISTRY_PULL_URL")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}REGISTRY_PULL_PATH")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}REGISTRY_PUSH_URL")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}REGISTRY_PUSH_PATH")
        globalPropertyManager.deleteGlobalProperty("${configuration.getNamePrefixForEnvVars()}K8S_VERSION")
    }
}
