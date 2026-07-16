package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager;
import com.cloudogu.gitops.infrastructure.jenkins.JobManager;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;

@Singleton
@Order(300)
public class JenkinsDestructionHandler implements DestructionHandler {

    private final JobManager jobManager;
    private final GlobalPropertyManager globalPropertyManager;
    private final Config config;

    public JenkinsDestructionHandler(JobManager jobManager,
                                     Config config,
                                     GlobalPropertyManager globalPropertyManager) {
        this.jobManager = jobManager;
        this.config = config;
        this.globalPropertyManager = globalPropertyManager;
    }

    @Override
    public void destroy() {
        jobManager.deleteJob(config.getApplication().getNamePrefix() + "example-apps");
        globalPropertyManager.deleteGlobalProperty("SCMM_URL");
        globalPropertyManager.deleteGlobalProperty(config.getApplication().getNamePrefixForEnvVars() + "REGISTRY_URL");
        globalPropertyManager.deleteGlobalProperty(config.getApplication().getNamePrefixForEnvVars() + "REGISTRY_PATH");
        globalPropertyManager.deleteGlobalProperty(config.getApplication().getNamePrefixForEnvVars() + "REGISTRY_PROXY_URL");
        globalPropertyManager.deleteGlobalProperty(config.getApplication().getNamePrefixForEnvVars() + "REGISTRY_PROXY_PATH");

        globalPropertyManager.deleteGlobalProperty(config.getApplication().getNamePrefixForEnvVars() + "K8S_VERSION");
    }
}
