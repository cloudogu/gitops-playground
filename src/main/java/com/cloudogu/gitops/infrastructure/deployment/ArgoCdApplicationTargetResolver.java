package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;

import jakarta.inject.Singleton;

@Singleton
public class ArgoCdApplicationTargetResolver {

    public ArgoCdApplicationTarget resolve(DeploymentContext context, String repoName) {
        Config config = context.getConfig();

        String namePrefix = config.getApplication().getNamePrefix() != null ? config.getApplication().getNamePrefix() : "";
        String prefix = namePrefix.strip();

        String applicationName = !prefix.isEmpty() ? prefix + repoName : repoName;
        String namespace = namePrefix + config.getFeatures().getArgocd().getNamespace();
        String project = "cluster-resources";

        boolean isOperatorMode = Boolean.TRUE.equals(config.getFeatures().getArgocd().getOperator());
        boolean createDestinationNamespace = !isOperatorMode;

        if (context.isMultiTenant()) {
            namespace = config.getMultiTenant().getCentralArgocdNamespace();
            project = prefix.replaceFirst("-$", "");
        }

        return new ArgoCdApplicationTarget(applicationName,
                namespace,
                project,
                createDestinationNamespace);
    }
}
