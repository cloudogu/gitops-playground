package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.content.ContentLoader;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.tools.CertManager;
import com.cloudogu.gitops.tools.ExternalSecretsOperator;
import com.cloudogu.gitops.tools.Ingress;
import com.cloudogu.gitops.tools.Monitoring;
import com.cloudogu.gitops.tools.Registry;
import com.cloudogu.gitops.tools.Vault;
import com.cloudogu.gitops.tools.common.AbstractTool;
import com.cloudogu.gitops.tools.core.Jenkins;
import com.cloudogu.gitops.tools.core.argocd.ArgoCD;
import com.cloudogu.gitops.tools.core.scmmanager.ScmManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class DeploymentOrchestrator {

@Getter private final List<AbstractTool> tools;

@Inject
public DeploymentOrchestrator(
	ScmManager scmManager,
	Jenkins jenkins,
	Registry registry,
	ArgoCD argoCD,
	Ingress ingress,
	CertManager certManager,
	Monitoring monitoring,
	ExternalSecretsOperator externalSecretsOperator,
	Vault vault,
	ContentLoader contentLoader) {
	this(
		List.of(
			scmManager,
			argoCD,
			jenkins,
			registry,
			ingress,
			certManager,
			monitoring,
			externalSecretsOperator,
			vault,
			contentLoader));
}

public void deployTools(DeploymentContext context, RepositoryWorkspace workspace) {
	log.debug("Starting tool orchestration.");

	for (AbstractTool tool : tools) {
	if (!tool.isEnabled(context)) {
		log.debug("Skipping disabled tool {}", tool.getClass().getSimpleName());
		continue;
	}

	log.debug("Deploying tool {}", tool.getClass().getSimpleName());
	tool.execute(context, workspace);
	}

	log.debug("Tool orchestration finished.");
}
}
