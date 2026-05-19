package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy
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

	String namespace = "${config.application.namePrefix}secrets"
	Config config
	K8sClient k8sClient

	ExternalSecretsOperator(Config config,
		FileSystemUtils fileSystemUtils,
		DeploymentStrategy deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler) {

		this.deployer = deployer
		this.config = config
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
	}

	@Override
	boolean isEnabled() {
		return config.features.secrets.active
	}

	@Override
	void enable() {
		def helmConfig = config.features.secrets.externalSecrets.helm
		deployHelmChart('external-secrets-operator', 'external-secrets', namespace, helmConfig, HELM_VALUES_PATH, config)
	}
}