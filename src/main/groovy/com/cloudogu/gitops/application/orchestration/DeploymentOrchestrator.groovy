package com.cloudogu.gitops.application.orchestration

import com.cloudogu.gitops.application.content.ContentLoader
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.tools.*
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.core.Jenkins
import com.cloudogu.gitops.tools.core.argocd.ArgoCD
import com.cloudogu.gitops.tools.core.scmmanager.ScmManager

import jakarta.inject.Inject
import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
class DeploymentOrchestrator {

	final List<Tool> tools

	@Inject
	DeploymentOrchestrator(ScmManager scmManager,
		Jenkins jenkins,
		Registry registry,
		ArgoCD argoCD,
		Ingress ingress,
		CertManager certManager,
		Monitoring monitoring,
		ExternalSecretsOperator externalSecretsOperator,
		Vault vault,
		ContentLoader contentLoader) {
		this([scmManager,
		      jenkins,
		      registry,
		      argoCD,
		      ingress,
		      certManager,
		      monitoring,
		      externalSecretsOperator,
		      vault,
		      contentLoader])
	}

	DeploymentOrchestrator(List<Tool> tools) {
		this.tools = tools
	}

	void execute(DeploymentContext context,
		RepositoryWorkspace workspace) {
		log.debug('Starting tool orchestration.')

		tools.each { Tool tool ->
			log.debug("Validating tool ${tool.class.simpleName}")
			tool.validate()
		}

		tools.each { Tool tool ->
			log.debug("Executing tool ${tool.class.simpleName}")
			tool.execute(context,
				workspace)
		}

		log.debug('Tool orchestration finished.')
	}
}