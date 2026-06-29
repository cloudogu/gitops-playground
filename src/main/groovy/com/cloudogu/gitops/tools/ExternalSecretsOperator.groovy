package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.common.ToolWithImage
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(400)
class ExternalSecretsOperator extends Tool implements ToolWithImage {

	static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/external-secrets/templates/values.ftl.yaml"

	String namespace
	K8sClient k8sClient

	ExternalSecretsOperator(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler) {
		this.deployer = deployer
		this.context = context
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
		this.namespace = "${config.application.namePrefix}${config.features.secrets.namespace}"
	}

	@Override
	boolean isEnabled() {
		return config.features.secrets.active
	}

	@Override
	void enable() {
		def helmConfig = config.features.secrets.externalSecrets.helm
		deployHelmChart('external-secrets-operator', 'external-secrets', namespace, helmConfig, HELM_VALUES_PATH, context)
	}
}