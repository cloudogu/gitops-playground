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
@Order(160)
class CertManager extends Tool implements ToolWithImage {

	static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/cert-manager/templates/values.ftl.yaml"

	final K8sClient k8sClient
	String namespace

	CertManager(FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler) {
		this.deployer = deployer
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.certManager.active
	}

	@Override
	protected void prepare() {
		this.namespace = activeNamespace(context)
	}

	@Override
	protected String activeNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}${context.config.features.certManager.namespace}"
	}

	@Override
	void enable() {
		deployHelmChart('cert-manager', 'cert-manager', namespace, config.features.certManager.helm, HELM_VALUES_PATH, context)
	}
}