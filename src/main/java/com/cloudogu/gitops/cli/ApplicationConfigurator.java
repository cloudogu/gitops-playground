package com.cloudogu.gitops.cli;

import com.cloudogu.gitops.config.Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;

@RequiredArgsConstructor
@Slf4j
public class ApplicationConfigurator {

	private static boolean hasText(String value) {
		return value != null && !value.isEmpty();
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return hasText(preferred) ? preferred : fallback;
	}

	/**
	 * Sets dynamic fields and validates params
	 */
	public Config initConfig(Config newConfig) {
		addAdditionalApplicationConfig(newConfig);
		addNamePrefix(newConfig);
		checkAndSetNamespaces(newConfig);
		addScmConfig(newConfig);
		addRegistryConfig(newConfig);
		addJenkinsConfig(newConfig);
		addFeatureConfig(newConfig);
		evaluateBaseUrl(newConfig);
		setResourceInclusionsCluster(newConfig);
		setMultiTenantModeConfig(newConfig);

		return newConfig;
	}

	private void addFeatureConfig(Config newConfig) {
		if (newConfig.getFeatures().getSecrets().getVault().getMode() != null) {
			newConfig.getFeatures().getSecrets().setActive(true);
		}

		if (hasText(newConfig.getFeatures().getMail().getSmtpAddress())) {
			newConfig.getFeatures().getMail().setActive(true);
		}

		if (newConfig.getFeatures().getIngress().getActive() && !hasText(newConfig.getApplication().getBaseUrl())) {
			log.warn(
				"Ingress-controller is activated without baseUrl parameter. Services will not be accessible by hostnames. To avoid this use baseUrl with ingress. ");
		}
	}

	private static void addNamePrefix(Config newConfig) {
		String namePrefix = newConfig.getApplication().getNamePrefix();
		if (hasText(namePrefix)) {
			if (!namePrefix.endsWith("-")) {
				newConfig.getApplication().setNamePrefix(namePrefix + "-");
			}
			newConfig.getApplication()
			         .setNamePrefixForEnvVars(newConfig.getApplication()
			                                           .getNamePrefix()
			                                           .toUpperCase()
			                                           .replace('-', '_'));
		}
	}

	private static void addRegistryConfig(Config newConfig) {
		// Process image pull secrets first, they might even be relevant if no registry is set
		if (newConfig.getRegistry().getCreateImagePullSecrets()) {
			String username = firstNonBlank(
				newConfig.getRegistry().getReadOnlyUsername(), newConfig.getRegistry()
				                                                        .getUsername()
			);
			String password = firstNonBlank(
				newConfig.getRegistry().getReadOnlyPassword(), newConfig.getRegistry()
				                                                        .getPassword()
			);

			if (!hasText(username) || !hasText(password)) {
				throw new IllegalArgumentException(
					"createImagePullSecrets needs to be used with either registry username and password or the readOnly variants");
			}
		}

		if (hasText(newConfig.getRegistry().getUrl())) {
			newConfig.getRegistry().setInternal(false);
			newConfig.getRegistry().setActive(true);
		} else if (newConfig.getRegistry().getActive()) {
	/* Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on
	docker push in the example application's Jenkins Jobs.
	Both setting up HTTPS or allowing insecure registry via daemon.json makes the playground difficult to use.
	So, always use localhost.
	Allow overriding the port, in case multiple playground instance run on a single host in different
	k3d clusters. */
			newConfig.getRegistry().setInternal(true);
			newConfig.getRegistry().setUrl("localhost:" + newConfig.getRegistry().getInternalPort());
		} else {
			// Registry not active, no need to set the following values
			return;
		}

		if (hasText(newConfig.getRegistry().getProxyUrl())) {
			newConfig.getRegistry().setTwoRegistries(true);
			if (!hasText(newConfig.getRegistry().getProxyUsername()) || !hasText(newConfig.getRegistry()
			                                                                              .getProxyPassword())) {
				throw new IllegalArgumentException("Proxy URL needs to be used with proxy-username and proxy-password");
			}
		}
	}

	private void addAdditionalApplicationConfig(Config newConfig) {
		if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
			log.debug("installation is running in kubernetes.");
			newConfig.getApplication().setRunningInsideK8s(true);
		}
	}

	private void addScmConfig(Config newConfig) {
		log.debug("Adding additional config for SCM");

		if (newConfig.getScm().getScmManager() != null && hasText(newConfig.getScm().getScmManager().getUrl())) {
			log.debug("Setting external scmm config");
			newConfig.getScm().getScmManager().setInternal(false);
			newConfig.getScm().getScmManager().setUrlForJenkins(newConfig.getScm().getScmManager().getUrl());
		} else {
			log.debug("Setting configs for internal SCM-Manager");
			newConfig.getScm().getScmManager().setInternal(true);
			// We use the K8s service as default name here, because it is the only option:
			// "scmm.localhost" will not work inside the Pods and k3d-container IP + Port (e.g.
			// 172.x.y.z:9091)
			// will not work on Windows and MacOS.
			String urlForJenkins = new StringBuilder("http://scmm.")
				.append(newConfig.getApplication().getNamePrefix())
				.append(newConfig.getScm().getScmManager().getNamespace())
				.append(".svc.cluster.local/scm")
				.toString();
			newConfig.getScm().getScmManager().setUrlForJenkins(urlForJenkins);
		}

		// We probably could get rid of some of the complexity by refactoring url, host and ingress into
		// a single var
		if (hasText(newConfig.getApplication().getBaseUrl())) {
			try {
				String scmUrl = injectSubdomain(
					"scmm",
					newConfig.getApplication().getBaseUrl(),
					newConfig.getApplication().getUrlSeparatorHyphen()
				);

				newConfig.getScm()
				         .getScmManager()
				         .setIngress(URI.create(scmUrl).toURL().getHost());

			} catch (IllegalArgumentException | MalformedURLException e) {
				throw new UncheckedIOException("Failed to evaluate SCM ingress URL", new IOException(e));
			}
		}

		// When specific user/pw are not set, set them to global values
		if (Config.DEFAULT_ADMIN_PW.equals(newConfig.getScm().getScmManager().getPassword())) {
			newConfig.getScm().getScmManager().setPassword(newConfig.getApplication().getPassword());
		}
		if (Config.DEFAULT_ADMIN_USER.equals(newConfig.getScm().getScmManager().getUsername())) {
			newConfig.getScm().getScmManager().setUsername(newConfig.getApplication().getUsername());
		}
	}

	private void addJenkinsConfig(Config newConfig) {
		log.debug("Adding additional config for Jenkins");
		if (hasText(newConfig.getJenkins().getUrl())) {
			log.debug("Setting external jenkins config");
			newConfig.getJenkins().setActive(true);
			newConfig.getJenkins().setInternal(false);
			newConfig.getJenkins().setUrlForScm(newConfig.getJenkins().getUrl());
		} else if (newConfig.getJenkins().getActive()) {
			log.debug("Setting configs for internal jenkins");
			// We use the K8s service as default name here, because it is the only option:
			// "jenkins.localhost" will not work inside the Pods and k3d-container IP + Port (e.g.
			// 172.x.y.z:9090)
			// will not work on Windows and MacOS.
			String defaultNamespace = newConfig.getJenkins().getNamespace();
			newConfig.getJenkins()
			         .setUrlForScm("http://jenkins." + newConfig.getApplication()
			                                                    .getNamePrefix() + defaultNamespace + ".svc.cluster.local");
		} else {
			// Jenkins not active, no need to set the following values
			return;
		}

		if (hasText(newConfig.getApplication().getBaseUrl())) {
			try {
				String jenkinsUrl = injectSubdomain(
					"jenkins",
					newConfig.getApplication().getBaseUrl(),
					newConfig.getApplication().getUrlSeparatorHyphen()
				);

				newConfig.getJenkins().setIngress(URI.create(jenkinsUrl).toURL().getHost());

			} catch (IllegalArgumentException | MalformedURLException e) {
				throw new UncheckedIOException("Failed to evaluate Jenkins ingress URL ", new IOException(e));
			}
		}

		// When specific user/pw are not set, set them to global values
		if (Config.DEFAULT_ADMIN_USER.equals(newConfig.getJenkins().getUsername())) {
			newConfig.getJenkins().setUsername(newConfig.getApplication().getUsername());
		}
		if (Config.DEFAULT_ADMIN_PW.equals(newConfig.getJenkins().getPassword())) {
			newConfig.getJenkins().setPassword(newConfig.getApplication().getPassword());
		}
	}

	private void evaluateBaseUrl(Config newConfig) {
		String baseUrl = newConfig.getApplication().getBaseUrl();
		if (!hasText(baseUrl)) {
			return;
		}
		log.debug("Base URL set, adapting to individual tools");
		Config.ArgoCDSchema argocd = newConfig.getFeatures().getArgocd();
		Config.MonitoringSchema monitoring = newConfig.getFeatures().getMonitoring();
		Config.SecretsSchema.VaultSchema vault = newConfig.getFeatures().getSecrets().getVault();
		boolean urlSeparatorHyphen = newConfig.getApplication().getUrlSeparatorHyphen();

		if (argocd.getActive() && !hasText(argocd.getUrl())) {
			argocd.setUrl(injectSubdomain("argocd", baseUrl, urlSeparatorHyphen));
			log.debug("Setting ArgoCD URL {}", argocd.getUrl());
		}
		if (monitoring.getActive() && !hasText(monitoring.getGrafanaUrl())) {
			monitoring.setGrafanaUrl(injectSubdomain("grafana", baseUrl, urlSeparatorHyphen));
			log.debug("Setting Monitoring URL {}", monitoring.getGrafanaUrl());
		}
		if (newConfig.getFeatures().getSecrets().getActive() && !hasText(vault.getUrl())) {
			vault.setUrl(injectSubdomain("vault", baseUrl, urlSeparatorHyphen));
			log.debug("Setting Vault URL {}", vault.getUrl());
		}
	}

	public void setMultiTenantModeConfig(Config newConfig) {
		if (newConfig.getMultiTenant().getUseDedicatedInstance()) {
			if (!hasText(newConfig.getApplication().getNamePrefix())) {
				throw new IllegalArgumentException(
					"To enable Central Multi-Tenant mode, you must define a name prefix to distinguish between instances.");
			}

			if (!newConfig.getFeatures().getArgocd().getOperator()) {
				newConfig.getFeatures().getArgocd().setOperator(true);
			}

			// Removes trailing slash from the input URL to avoid duplicated slashes in further URL
			// handling
			if (newConfig.getMultiTenant().getScmManager().getUrl() != null) {
				String urlString = newConfig.getMultiTenant().getScmManager().getUrl();
				if (urlString.endsWith("/")) {
					urlString = urlString.substring(0, urlString.length() - 1);
				}
				newConfig.getMultiTenant().getScmManager().setUrl(urlString);
			}

			// Disabling Ingress in DedicatedInstances Mode for now.
			newConfig.getFeatures().getIngress().setActive(false);
		}
	}

	private static String injectSubdomain(String subdomain, String baseUrl, boolean urlSeparatorHyphen) {
		try {
			URI uri = URI.create(baseUrl);

			String separator = urlSeparatorHyphen ? "-" : ".";

			StringBuilder newUrl = new StringBuilder(uri.getScheme())
				.append("://")
				.append(subdomain)
				.append(separator)
				.append(uri.getHost());

			if (uri.getPort() != -1) {
				newUrl.append(":").append(uri.getPort());
			}

			// getRawPath() preserves URL encoding (like %20), matching the old URL.getPath() behavior
			if (uri.getRawPath() != null) {
				newUrl.append(uri.getRawPath());
			}

			return newUrl.toString();

		} catch (IllegalArgumentException e) {
			throw new UncheckedIOException(
				"Failed to inject subdomain '" + subdomain + "' into base URL: " + baseUrl,
				new IOException(e)
			);
		}
	}

	private void setResourceInclusionsCluster(Config configToSet) {
		// Return early if NOT deploying via operator
		if (!configToSet.getFeatures().getArgocd().getOperator()) {
			log.debug("ArgoCD operator is not enabled. Skipping features.argocd.resourceInclusionsCluster setup.");
			return;
		}
		log.info("Starting setup of features.argocd.resourceInclusionsCluster for ArgoCD Operator");

		if (!isUrlSetAndValid(configToSet)) {
			buildAndValidateURLFromEnvironment(configToSet);
		}
	}

	public boolean isUrlSetAndValid(Config config) {
		String url = config.getFeatures().getArgocd().getResourceInclusionsCluster();

		if (hasText(url)) {
			try {
				log.debug("Validating user-provided features.argocd.resourceInclusionsCluster URL: {}", url);

				// Java 20+ compliant URL validation
				URI.create(url).toURL();

				log.info("Found valid URL in features.argocd.resourceInclusionsCluster: {}", url);
				return true;
			} catch (IllegalArgumentException | MalformedURLException e) {
				throw new IllegalArgumentException(
					"Invalid URL for 'features.argocd.resourceInclusionsCluster': " + url + ".",
					e
				);
			}
		}
		return false;
	}

	public void buildAndValidateURLFromEnvironment(Config config) {
		log.debug("Attempting to set features.argocd.resourceInclusionsCluster via Kubernetes ENV variables.");

		String host = System.getenv("KUBERNETES_SERVICE_HOST");
		String port = System.getenv("KUBERNETES_SERVICE_PORT");

		String errorMessage = "Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. " + "Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly. " + "Alternatively, try setting 'features.argocd.resourceInclusionsCluster' in the config to manually override.";

		if (!hasText(host) || !hasText(port)) {
			throw new IllegalStateException(errorMessage);
		}

		String internalClusterUrl = "https://" + host + ":" + port;
		log.debug("Constructed internal Kubernetes API Server URL: {}", internalClusterUrl);

		try {
			URI.create(internalClusterUrl).toURL();
			config.getFeatures().getArgocd().setResourceInclusionsCluster(internalClusterUrl);
			log.info(
				"Successfully set features.argocd.resourceInclusionsCluster via Kubernetes ENV to: {}",
				internalClusterUrl
			);
		} catch (IllegalArgumentException | MalformedURLException e) {
			throw new IllegalArgumentException(errorMessage, e);
		}
	}

	public void checkAndSetNamespaces(Config config) {
		if (hasText(config.getApplication().getNamespace())) {
			String namespace = config.getApplication().getNamespace();
			config.getApplication().setGopNamespace(namespace);
			config.getRegistry().setNamespace(namespace);
			config.getJenkins().setNamespace(namespace);
			config.getScm().getScmManager().setNamespace(namespace);
			config.getFeatures().getArgocd().setNamespace(namespace);
			config.getFeatures().getMonitoring().setNamespace(namespace);
			config.getFeatures().getSecrets().setNamespace(namespace);
			config.getFeatures().getIngress().setIngressNamespace(namespace);
			config.getFeatures().getCertManager().setNamespace(namespace);

			config.getContent().getNamespaces().clear();
			String contentNamespace = config.getApplication().getNamePrefix() + namespace;
			config.getContent().getNamespaces().add(contentNamespace);
		}
	}
}
