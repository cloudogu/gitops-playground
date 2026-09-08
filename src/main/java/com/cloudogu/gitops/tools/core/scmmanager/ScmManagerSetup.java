package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerUser;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.TemplateModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class ScmManagerSetup {

	private static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/scm-manager/templates/values.ftl.yaml";
	private static final long MILLIS_PER_SECOND = 1000L;
	private static final int SCMM_AVAILABILITY_TIMEOUT_SECONDS = 180;
	private static final int SCMM_AVAILABILITY_POLL_INTERVAL_MILLIS = 5000;
	private static final int SCMM_RESTART_POLL_INTERVAL_MILLIS = 2000;
	private static final int SCMM_RESTART_START_DELAY_MILLIS = 100;
	private static final int DEFAULT_PROXY_PORT = 8080;
	private static final int DEFAULT_LOGIN_ATTEMPT_LIMIT_TIMEOUT_SECONDS = 300;

	private final ScmManagerProvider scmManager;
	private final Deployer deployer;
	private final DeploymentContext context;
	private final RepositoryWorkspace repositoryWorkspace;
	private final FileSystemUtils fileSystemUtils;
	private final ScmManagerToolConfig config;

	private Path tempValuesPath;

	public void setupHelm() {
		Path valuesPath = prepareHelmValues();
		HelmChartConfig helmConfig = config.helm();
		String releaseName = scmmReleaseName();

		log.info(
			"Deploying SCM-Manager via Helm with releaseName='{}', namespace='{}', namePrefix='{}', dedicatedInstance={}",
			releaseName,
			config.namespace(),
			config.namePrefix(),
			config.multiTenant()
		);

		deployer.getHelmStrategy()
				.deployFeature(
					helmConfig.repoURL(),
					"scm-manager",
					helmConfig.chart(),
					helmConfig.version(),
					config.namespace(),
					releaseName,
					valuesPath,
					DeploymentStrategy.RepoType.HELM
				);
	}

	public void createArgocdApplication() {
		Path valuesPath = tempValuesPath != null ? tempValuesPath : prepareHelmValues();
		HelmChartConfig helmConfig = config.helm();
		String releaseName = scmmReleaseName();

		log.info(
			"Creating SCM-Manager ArgoCD application with releaseName='{}', namespace='{}', namePrefix='{}', dedicatedInstance={}",
			releaseName,
			config.namespace(),
			config.namePrefix(),
			config.multiTenant()
		);

		deployer.deployFeature(
			helmConfig.repoURL(),
			"scm-manager",
			helmConfig.chart(),
			helmConfig.version(),
			config.namespace(),
			releaseName,
			valuesPath,
			DeploymentStrategy.RepoType.HELM,
			false,
			context,
			repositoryWorkspace
		);
	}

	public void prepareBootstrapRepositoriesAfterScmManagerDeployment() {
		try {
			repositoryWorkspace.ensureRemoteRepositoriesExist();
			repositoryWorkspace.initLocalRepositoriesIfNeeded();
			repositoryWorkspace.alignWithRemoteMainIfPresent();
			repositoryWorkspace.createLocalDirectories();
		} catch (Exception e) {
			throw new RuntimeException("Failed to prepare bootstrap repositories", e);
		}
	}

	public void pushBootstrapRepositoriesAfterScmManagerDeployment() {
		try {
			repositoryWorkspace.commitAndPushClusterResourcesChanges(
				"Bootstrap cluster-resources repository after SCM-Manager deployment");

			if (repositoryWorkspace.hasTenantBootstrapRepository()) {
				repositoryWorkspace.commitAndPushTenantBootstrapChanges(
					"Bootstrap tenant repository after SCM-Manager deployment");
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to push bootstrap repositories", e);
		}
	}

	private Path prepareHelmValues() {
		String releaseName = scmmReleaseName();

		log.debug(
			"Preparing SCM-Manager Helm values with releaseName='{}', namespace='{}'",
			releaseName,
			config.namespace()
		);

		Map<String, Object> templateVars = new HashMap<>();
		templateVars.put("config", config.templateConfig());
		templateVars.put("host", config.ingress());
		templateVars.put("username", config.username());
		templateVars.put("password", config.password());
		templateVars.put("helm", config.helm());
		templateVars.put("releaseName", releaseName);

		try {
			TemplateModel statics = new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build()
																								 .getStaticModels();
			templateVars.put("statics", statics);
		} catch (Exception e) {
			throw new RuntimeException("Failed to expose freemarker statics model", e);
		}

		Map<String, Object> templatedMap = TemplatingEngine.templateToMap(HELM_VALUES_PATH, templateVars);
		Map<String, Object> values = config.helm().values();

		Map<String, Object> mergedMap = MapUtils.deepMerge(values, templatedMap);
		tempValuesPath = fileSystemUtils.writeTempFile(mergedMap);

		return tempValuesPath;
	}

	private String scmmReleaseName() {
		return config.releaseName();
	}

	public void waitForScmmAvailable() {
		waitForScmmAvailable(SCMM_AVAILABILITY_TIMEOUT_SECONDS, SCMM_AVAILABILITY_POLL_INTERVAL_MILLIS, 0);
	}

	public void waitForScmmAvailable(int timeoutSeconds, int intervalMillis, int startDelay) {
		long startTime = System.currentTimeMillis();
		long timeoutMillis = timeoutSeconds * MILLIS_PER_SECOND;

		if (startDelay > 0) {
			try {
				Thread.sleep(startDelay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for SCM-Manager", e);
			}
		}

		while (System.currentTimeMillis() - startTime < timeoutMillis) {
			try {
				retrofit2.Call<Void> call = scmManager.getApiClient().generalApi().checkScmmAvailable();
				retrofit2.Response<Void> response = call.execute();

				if (response.isSuccessful()) {
					log.debug("SCM-Manager is available.");
					return;
				}
			} catch (Exception e) {
				log.debug("Waiting for SCM-Manager... Error: {}", e.getMessage());
			}

			try {
				Thread.sleep(intervalMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for SCM-Manager", e);
			}
		}

		throw new IllegalStateException("Timeout: SCM-Manager did not respond with 200 OK within " + timeoutSeconds + " seconds");
	}

	public void configure() {
		installScmmPlugins();
		setSetupConfigs();

		if (config.jenkinsActive()) {
			configureJenkinsPlugin();
		}

		addDefaultUsers();

		log.info("ScmManager Setup finished!");
	}

	private void installScmmPlugins() {
		if (config.skipPlugins()) {
			log.debug("Skipping SCM plugin installation");
			return;
		}

		List<String> pluginNames = new ArrayList<>(List.of(
			"scm-mail-plugin",
			"scm-review-plugin",
			"scm-code-editor-plugin",
			"scm-editor-plugin",
			"scm-landingpage-plugin",
			"scm-el-plugin",
			"scm-readme-plugin",
			"scm-webhook-plugin",
			"scm-ci-plugin",
			"scm-metrics-prometheus-plugin"
		));

		if (config.jenkinsActive()) {
			pluginNames.add("scm-jenkins-plugin");
		}

		boolean restartForThisPlugin = false;

		for (int i = 0; i < pluginNames.size(); i++) {
			String pluginName = pluginNames.get(i);
			log.debug("Installing Plugin {} ...", pluginName);

			restartForThisPlugin = !config.skipRestart() && i == pluginNames.size() - 1;

			ScmManagerApiClient.handleApiResponse(scmManager.getApiClient()
															.pluginApi()
															.install(pluginName, restartForThisPlugin));
		}

		log.debug("SCM-Manager plugin installation finished successfully!");

		if (restartForThisPlugin) {
			waitForScmmAvailable(
				SCMM_AVAILABILITY_TIMEOUT_SECONDS,
				SCMM_RESTART_POLL_INTERVAL_MILLIS,
				SCMM_RESTART_START_DELAY_MILLIS
			);
		}
	}

	private void setSetupConfigs() {
		Map<String, Object> setupConfigs = new HashMap<>();
		setupConfigs.put("enableProxy", false);
		setupConfigs.put("proxyPort", DEFAULT_PROXY_PORT);
		setupConfigs.put("proxyServer", "proxy.mydomain.com");
		setupConfigs.put("proxyUser", null);
		setupConfigs.put("proxyPassword", null);
		setupConfigs.put("realmDescription", "SONIA :: SCM Manager");
		setupConfigs.put("disableGroupingGrid", false);
		setupConfigs.put("dateFormat", "YYYY-MM-DD HH:mm:ss");
		setupConfigs.put("anonymousAccessEnabled", false);
		setupConfigs.put("anonymousMode", "OFF");
		setupConfigs.put("baseUrl", this.scmManager.getUrl());
		setupConfigs.put("forceBaseUrl", false);
		setupConfigs.put("loginAttemptLimit", -1);
		setupConfigs.put("proxyExcludes", new ArrayList<>());
		setupConfigs.put("skipFailedAuthenticators", false);
		setupConfigs.put(
			"pluginUrl",
			"https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}"
		);
		setupConfigs.put("loginAttemptLimitTimeout", DEFAULT_LOGIN_ATTEMPT_LIMIT_TIMEOUT_SECONDS);
		setupConfigs.put("enabledXsrfProtection", true);
		setupConfigs.put("namespaceStrategy", "CustomNamespaceStrategy");
		setupConfigs.put("loginInfoUrl", "https://login-info.scm-manager.org/api/v1/login-info");
		setupConfigs.put("releaseFeedUrl", "https://scm-manager.org/download/rss.xml");
		setupConfigs.put("mailDomainName", "scm-manager.local");
		setupConfigs.put("adminGroups", new ArrayList<>());
		setupConfigs.put("adminUsers", new ArrayList<>());

		ScmManagerApiClient.handleApiResponse(scmManager.getApiClient().generalApi().setConfig(setupConfigs));

		log.debug("Successfully added SCMM Setup Configs");
	}

	private void configureJenkinsPlugin() {
		Map<String, Object> jenkinsPluginConfig = new HashMap<>();
		jenkinsPluginConfig.put("disableRepositoryConfiguration", false);
		jenkinsPluginConfig.put("disableMercurialTrigger", false);
		jenkinsPluginConfig.put("disableGitTrigger", false);
		jenkinsPluginConfig.put("disableEventTrigger", false);
		jenkinsPluginConfig.put("url", config.jenkinsUrl());

		ScmManagerApiClient.handleApiResponse(this.scmManager.getApiClient()
															 .pluginApi()
															 .configureJenkinsPlugin(jenkinsPluginConfig));

		log.debug("Successfully configured JenkinsPlugin in SCM-Manager.");
	}

	private void addDefaultUsers() {
		String metricsUsername = config.namePrefix() + "metrics";

		addUser(
			config.gitOpsUsername(), config.password(), "changeme@test.local"
		);
		addUser(metricsUsername, config.password(), "changeme@test.local");
		grantUserPermissions(metricsUsername, List.of("metrics:read"));
	}

	private void addUser(String username, String password, String email) {
		ScmManagerUser userRequest = new ScmManagerUser();
		userRequest.setName(username);
		userRequest.setDisplayName(username);
		userRequest.setMail(email);
		userRequest.setExternal(false);
		userRequest.setPassword(password);
		userRequest.setActive(true);
		userRequest.setLinks(new HashMap<>());

		ScmManagerApiClient.handleApiResponse(scmManager.getApiClient().usersApi().addUser(userRequest));

		log.debug("Successfully created SCM-Manager User {}.", username);
	}

	private void grantUserPermissions(String username, List<String> permissions) {
		Map<String, List<String>> permissionBody = new HashMap<>();
		permissionBody.put("permissions", permissions);

		ScmManagerApiClient.handleApiResponse(scmManager.getApiClient()
														.usersApi()
														.setPermissionForUser(username, permissionBody));

		log.debug("Granted permissions {} to user {}.", permissions, username);
	}
}
