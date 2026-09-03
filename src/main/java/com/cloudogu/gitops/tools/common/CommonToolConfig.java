package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;

import java.util.function.Consumer;

public class CommonToolConfig implements ConfigLifecycleHook {

	private final K8sClient k8sClient;

	public CommonToolConfig(K8sClient k8sClient) {
		this.k8sClient = k8sClient;
	}

	@Override
	public void preConfigInit(Config configToSet) {
		validateConfig(configToSet);

		extractCredentials(configToSet);
	}

	private void extractCredentials(Config config) {
		var application = config.getApplication();
		resolve(
			application.getCredentials(), resolved -> {
				application.setCredentials(resolved);
				application.setUsername(resolved.getUsername());
				application.setPassword(resolved.getPassword());
			}
		);

		var jenkins = config.getJenkins();
		resolve(
			jenkins.getCredentials(), resolved -> {
				jenkins.setCredentials(resolved);
				jenkins.setUsername(resolved.getUsername());
				jenkins.setPassword(resolved.getPassword());
			}
		);
		resolve(
			jenkins.getMetricsCredentials(), resolved -> {
				jenkins.setMetricsCredentials(resolved);
				jenkins.setMetricsUsername(resolved.getUsername());
				jenkins.setMetricsPassword(resolved.getPassword());
			}
		);

		var registry = config.getRegistry();
		resolve(
			registry.getCredentials(), resolved -> {
				registry.setCredentials(resolved);
				registry.setUsername(resolved.getUsername());
				registry.setPassword(resolved.getPassword());
			}
		);
		resolve(
			registry.getProxyCredentials(), resolved -> {
				registry.setProxyCredentials(resolved);
				registry.setProxyUsername(resolved.getUsername());
				registry.setProxyPassword(resolved.getPassword());
			}
		);
		var scmManager = config.getScm().getScmManager();

		if (scmManager != null) {
			resolve(
				scmManager.getCredentials(), resolved -> {
					scmManager.setCredentials(resolved);
					scmManager.setUsername(resolved.getUsername());
					scmManager.setPassword(resolved.getPassword());
				}
			);
		}
		var gitlab = config.getScm().getGitlab();
		if (gitlab != null) {
			resolve(
				gitlab.getCredentials(), resolved -> {
					gitlab.setCredentials(resolved);
					gitlab.setUsername(resolved.getUsername());
					gitlab.setPassword(resolved.getPassword());
				}
			);
		}

		var centralScmManager = config.getMultiTenant().getScmManager();
		if (centralScmManager != null) {
			resolve(
				centralScmManager.getCredentials(), resolved -> {
					centralScmManager.setCredentials(resolved);
					centralScmManager.setUsername(resolved.getUsername());
					centralScmManager.setPassword(resolved.getPassword());
				}
			);
		}

		var centralGitlab = config.getMultiTenant().getGitlab();
		if (centralGitlab != null) {
			resolve(
				centralGitlab.getCredentials(), resolved -> {
					centralGitlab.setCredentials(resolved);
					centralGitlab.setUsername(resolved.getUsername());
					centralGitlab.setPassword(resolved.getPassword());
				}
			);
		}

	}

	private void resolve(
		Credentials reference,
		Consumer<Credentials> apply) {
		if (reference == null || !reference.isUsed()
			|| reference.getSecretName().isBlank()) {
			return;
		}

		apply.accept(k8sClient.getCredentialsFromSecret(reference));
	}

	/**
	 * Make sure that config does not contain contradictory values. Throws RuntimeException with
	 * meaningful message, if invalid.
	 */
	public void validateConfig(Config configToSet) {
		validateMirrorReposHelmChartFolderSet(configToSet);
	}

	private static void validateMirrorReposHelmChartFolderSet(Config configToSet) {
		if (configToSet.getApplication().getMirrorRepos() && (configToSet.getApplication()
																		 .getLocalHelmChartFolder() == null || configToSet.getApplication()
																														  .getLocalHelmChartFolder()
																														  .isEmpty())) {
			// This should only happen when run outside the image, i.e. during development
			throw new IllegalArgumentException("Missing config for localHelmChartFolder.\n" + "Either run inside the official container image or setting env var " + "LOCAL_HELM_CHART_FOLDER='charts' after running 'scripts/downloadHelmCharts.sh' from the repo");
		}
	}
}
