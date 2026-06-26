package com.cloudogu.gitops.application.context

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType

class DeploymentContext {

	final Config config
	final TenantMode tenantMode
	final ScmManagerMode scmManagerMode
	final Boolean certManagerActive
	final Boolean airgapped
	final ScmProviderType centralScmProviderType
	final ScmProviderType tenantScmProviderType
	final Platform platform

	DeploymentContext(Config config,
		TenantMode tenantMode,
		ScmManagerMode scmManagerMode,
		Boolean certManagerActive,
		Boolean airgapped,
		ScmProviderType centralScmProviderType,
		ScmProviderType tenantScmProviderType,
		Platform platform) {
		this.config = config
		this.tenantMode = tenantMode
		this.scmManagerMode = scmManagerMode
		this.certManagerActive = certManagerActive
		this.airgapped = airgapped
		this.centralScmProviderType = centralScmProviderType
		this.tenantScmProviderType = tenantScmProviderType
		this.platform = platform
	}

	Boolean isMultiTenant() {
		return tenantMode == TenantMode.MULTI_TENANT
	}

	Boolean isSingleTenant() {
		return tenantMode == TenantMode.SINGLE_TENANT
	}

	Boolean isInternalScmManager() {
		return scmManagerMode == ScmManagerMode.INTERNAL
	}

	Boolean isExternalScmManager() {
		return scmManagerMode == ScmManagerMode.EXTERNAL
	}

	Boolean isNonAirgapped() {
		return !airgapped
	}

	Boolean isOpenshift() {
		return platform == Platform.OPENSHIFT
	}

	enum TenantMode {
		SINGLE_TENANT,
		MULTI_TENANT
	}

	enum ScmManagerMode {
		INTERNAL,
		EXTERNAL
	}

	enum Platform {
		KUBERNETES,
		OPENSHIFT
	}
}