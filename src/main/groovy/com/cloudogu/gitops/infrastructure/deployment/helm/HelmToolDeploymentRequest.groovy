package com.cloudogu.gitops.infrastructure.deployment.helm

import com.cloudogu.gitops.config.Config

import groovy.transform.CompileStatic

/**
 * Describes a Helm-based tool deployment.
 *
 * The request contains tool-specific deployment information.
 * Runtime infrastructure such as DeploymentContext and RepositoryWorkspace
 * is passed separately to HelmToolDeployer.*/
@CompileStatic
class HelmToolDeploymentRequest {

	final String toolName
	final String releaseName
	final String namespace
	final Config.HelmConfigWithValues helmConfig
	final String helmValuesPath
	final Map<String, Object> templateData
	final boolean bootstrapWithHelm

	HelmToolDeploymentRequest(String toolName,
		String releaseName,
		String namespace,
		Config.HelmConfigWithValues helmConfig,
		String helmValuesPath,
		Map<String, Object> templateData = [:],
		boolean bootstrapWithHelm = false) {
		this.toolName = toolName
		this.releaseName = releaseName
		this.namespace = namespace
		this.helmConfig = helmConfig
		this.helmValuesPath = helmValuesPath
		this.templateData = templateData ?: [:]
		this.bootstrapWithHelm = bootstrapWithHelm
	}
}