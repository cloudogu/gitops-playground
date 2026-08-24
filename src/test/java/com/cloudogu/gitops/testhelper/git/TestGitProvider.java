package com.cloudogu.gitops.testhelper.git;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestGitProvider {

	private TestGitProvider() {
	}

	public static Map<String, GitProvider> buildProviders(Config config) {
		boolean dedicatedInstance = Boolean.TRUE.equals(config.getMultiTenant().getUseDedicatedInstance());

		if (config.getScm().getScmProviderType() == ScmProviderType.GITLAB) {
			GitlabMock gitlab = new GitlabMock();
			gitlab.setBase(URI.create(config.getScm().getGitlab().getUrl()));
			gitlab.setNamePrefix(config.getApplication().getNamePrefix());
			return providers(gitlab, dedicatedInstance ? gitlab : null);
		}

		String namePrefix = config.getApplication().getNamePrefix();
		String serviceDns = "http://scmm." + namePrefix + "scm-manager.svc.cluster.local/scm";
		String tenantInCluster = valueOrDefault(
			config.getScm().getScmManager() == null ? null : config.getScm().getScmManager().getUrl(),
			serviceDns
		);
		String centralInCluster = valueOrDefault(
			config.getMultiTenant().getScmManager() == null ? null : config.getMultiTenant().getScmManager().getUrl(),
			tenantInCluster
		);

		ScmManagerProviderMock tenant = scmManagerProvider(tenantInCluster, namePrefix);
		ScmManagerProviderMock central = dedicatedInstance
			? scmManagerProvider(centralInCluster, namePrefix)
			: null;
		return providers(tenant, central);
	}

	private static ScmManagerProviderMock scmManagerProvider(String inClusterBase, String namePrefix) {
		ScmManagerProviderMock provider = new ScmManagerProviderMock();
		provider.setInClusterBase(URI.create(inClusterBase));
		provider.setNamePrefix(namePrefix);
		return provider;
	}

	private static Map<String, GitProvider> providers(GitProvider tenant, GitProvider central) {
		Map<String, GitProvider> providers = new LinkedHashMap<>();
		providers.put("tenant", tenant);
		providers.put("central", central);
		return providers;
	}

	private static String valueOrDefault(String value, String defaultValue) {
		return value == null || value.isEmpty() ? defaultValue : value;
	}
}
