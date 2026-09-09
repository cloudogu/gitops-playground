package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.regex.Pattern;

@Singleton
@RequiredArgsConstructor
public class ArgoCdApplicationTargetResolver {

	private static final Pattern TRAILING_DASH = Pattern.compile("-$");

	private final Config config;

	public ArgoCdApplicationTarget resolve(DeploymentContext context, String repoName) {

		String namePrefix = config.getApplication().getNamePrefix() != null ? config.getApplication()
																					.getNamePrefix() : "";
		String prefix = namePrefix.strip();

		String applicationName = !prefix.isEmpty() ? (prefix + repoName) : repoName;
		String namespace = namePrefix + config.getFeatures().getArgocd().getNamespace();
		String project = "cluster-resources";

		boolean isOperatorMode = config.getFeatures().getArgocd().getOperator();
		boolean createDestinationNamespace = !isOperatorMode;

		if (context.isMultiTenant()) {
			namespace = config.getMultiTenant().getCentralArgocdNamespace();
			project = TRAILING_DASH.matcher(prefix).replaceFirst("");
		}

		return new ArgoCdApplicationTarget(applicationName, namespace, project, createDestinationNamespace);
	}
}
