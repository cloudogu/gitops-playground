package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Order(10)
@Slf4j
public class ScmManager extends AbstractMappedTool<ScmManagerToolConfig> {

	@Getter
	@Setter
	private String namespace;
	private final ImagePullSecretCreator imagePullSecretCreator;
	private final ScmManagerConfigUpdater configUpdater;
	private ScmManagerSetup setup;

	public ScmManager(
		GitHandler gitHandler,
		Deployer deployer,
		FileSystemUtils fileSystemUtils,
		AirGappedUtils airGappedUtils,
		ImagePullSecretCreator imagePullSecretCreator,
		ScmManagerToolConfigMapper configMapper,
		ScmManagerConfigUpdater configUpdater) {
		super(configMapper);
		this.gitHandler = gitHandler;
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.airGappedUtils = airGappedUtils;
		this.imagePullSecretCreator = imagePullSecretCreator;
		this.configUpdater = configUpdater;
	}

	@Override
	protected boolean isEnabled(ScmManagerToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		log.info("Preparing internal SCM-Manager deployment.");

		prepareNamespace();
		imagePullSecretCreator.createIfRequired(toolConfig().imagePullSecret(), namespace);

		ScmManagerProvider scmManager = getTenantScmManager();

		this.setup = new ScmManagerSetup(
			scmManager, deployer, context, repositoryWorkspace, fileSystemUtils, toolConfig()
		);
	}

	@Override
	protected void deploy() {
		log.info("Deploying internal SCM-Manager.");

		setup.setupHelm();
		setup.waitForScmmAvailable();
	}

	@Override
	protected void postDeploy() {
		log.info("Configuring internal SCM-Manager after deployment.");

		setup.configure();

		/*
		 * Special bootstrap preparation:
		 * Creates/initializes the remote repositories and prepares the local workspace
		 * from the remote main branch before generated GitOps artifacts are written.
		 */
		setup.prepareBootstrapRepositoriesAfterScmManagerDeployment();

		/*
		 * The SCM-Manager ArgoCD Application is created through ArgoCdApplicationStrategy.
		 * The strategy writes into the shared RepositoryWorkspace and does not push itself.
		 */
		setup.createArgocdApplication();
	}

	@Override
	protected void publishChanges() {
		/*
		 * Push the complete bootstrap state, including generated SCM-Manager GitOps artifacts.
		 */
		setup.pushBootstrapRepositoriesAfterScmManagerDeployment();

		log.info("Internal SCM-Manager setup finished.");
	}

	private void prepareNamespace() {
		this.namespace = activeNamespace(toolConfig());
		configUpdater.updateNamespace(context, namespace);
	}

	@Override
	protected String activeNamespace(ScmManagerToolConfig config) {
		return config.namespace();
	}

	private ScmManagerProvider getTenantScmManager() {
		GitProvider tenantScm = gitHandler.getTenant();

		if (!(tenantScm instanceof ScmManagerProvider)) {
			throw new IllegalStateException("Tenant SCM provider is not an SCM-Manager. Actual provider: " + (tenantScm != null ? tenantScm.getClass()
			                                                                                                                               .getSimpleName() : "null"));
		}

		return (ScmManagerProvider) tenantScm;
	}
}
