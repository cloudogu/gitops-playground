package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.GitlabMock;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.utils.NetworkingUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GitHandlerTest {

	private static Config config() {
		return config(Map.of());
	}

	private static Config config(Map<String, ?> overrides) {
		Map<String, Object> base = new LinkedHashMap<>();
		base.put("application", Map.of("namePrefix", ""));
		base.put(
			"scm", Map.of(
				"scmProviderType", ScmProviderType.SCM_MANAGER,
				"scmManager", Map.of("internal", true),
				"gitlab", Map.of("url", "")
			)
		);
		base.put(
			"multiTenant", Map.of(
				"scmManager", Map.of("url", ""),
				"gitlab", Map.of("url", ""),
				"useDedicatedInstance", false
			)
		);

		Map<String, Object> merged = deepMerge(base, overrides);
		return Config.fromMap(merged);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> deepMerge(Map<String, Object> left, Map<String, ?> right) {
		Map<String, Object> out = new LinkedHashMap<>(left);

		right.forEach((key, value) -> {
			Object leftValue = left.get(key);
			if (value instanceof Map<?, ?> valueMap && leftValue instanceof Map<?, ?> leftMap) {
				out.put(
					key,
					deepMerge((Map<String, Object>) leftMap, (Map<String, ?>) valueMap)
				);
			} else {
				out.put(key, value);
			}
		});

		return out;
	}

	private static GitHandler handler(Config config) {
		return new GitHandler(
			mock(K8sClient.class),
			mock(NetworkingUtils.class),
			config
		);
	}

	private static DeploymentContext context(Config config) {
		return new ContextBuilder(config).build();
	}

	// ---------- validate() ------------------------------------------------------------

	@Test
	void validateScmManagerSelectedAndGitopsUsernameReceivesNamePrefix() {
		Config config = config(Map.of(
			"application", Map.of("namePrefix", "fv40-"),
			"scm", Map.of(
				"scmManager", Map.of(
					"url", "https://scmm.example.com/scm",
					"internal", true
				)
			)
		));

		GitHandler gitHandler = handler(config);

		gitHandler.validate();

		assertEquals(ScmProviderType.SCM_MANAGER, config.getScm().getScmProviderType());
		assertEquals("fv40-gitops", config.getScm().getScmManager().getGitOpsUsername());
	}

	@Test
	void validateGitLabChosenProviderSwitchedScmmNulledMissingPatOrParentGroupIdThrows() {
		Config config = config(Map.of(
			"scm", Map.of("gitlab", Map.of("url", "https://gitlab.example.com"))
		));

		GitHandler gitHandler = handler(config);

		RuntimeException exception = assertThrows(RuntimeException.class, gitHandler::validate);

		assertTrue(exception.getMessage().toLowerCase().contains("gitlab"));
		assertEquals(ScmProviderType.GITLAB, config.getScm().getScmProviderType());
		assertNull(config.getScm().getScmManager());
	}

	// ---------- getResourcesScm() -----------------------------------------------------

	@Test
	void getResourcesScmCentralWinsOverTenant() {
		GitHandler gitHandler = handler(config());

		gitHandler.setTenant(mock(GitProvider.class, "tenant"));
		gitHandler.setCentral(mock(GitProvider.class, "central"));

		assertSame(gitHandler.getCentral(), gitHandler.getResourcesScm());
	}

	@Test
	void getResourcesScmTenantReturnedWhenCentralAbsentThrowsWhenNone() {
		GitHandler gitHandler = handler(config());

		gitHandler.setTenant(mock(GitProvider.class));

		assertSame(gitHandler.getTenant(), gitHandler.getResourcesScm());

		gitHandler.setTenant(null);

		IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			gitHandler::getResourcesScm
		);

		assertTrue(exception.getMessage().contains("No SCM provider"));
	}

	// ---------- prepareProviders(): SCM_MANAGER ---------------------------------------

	@Test
	void prepareProvidersScmManagerTenantOnlyCreatesTenantProviderOnly() {
		Config config = Config.fromMap(Map.of(
			"scm", Map.of(
				"scmManager", Map.of("internal", true),
				"gitlab", Map.of("url", "")
			),
			"multiTenant", Map.of("useDedicatedInstance", false)
		));

		ScmManagerProviderMock tenant = new ScmManagerProviderMock();
		GitHandlerForTests gitHandler = new GitHandlerForTests(tenant);

		gitHandler.prepareProviders(context(config));

		assertEquals("scm-manager", config.getScm().getScmManager().getNamespace());

		assertSame(tenant, gitHandler.getTenant());
		assertNull(gitHandler.getCentral());
		assertSame(tenant, gitHandler.getResourcesScm());
	}

	@Test
	void prepareProvidersScmManagerTenantOnlyDoesNotCreateRepositories() {
		Config config = Config.fromMap(Map.of(
			"scm", Map.of(
				"scmManager", Map.of("internal", true),
				"gitlab", Map.of("url", "")
			),
			"multiTenant", Map.of("useDedicatedInstance", false)
		));

		ScmManagerProviderMock tenant = new ScmManagerProviderMock();
		GitHandlerForTests gitHandler = new GitHandlerForTests(tenant);

		gitHandler.prepareProviders(context(config));

		assertTrue(tenant.getCreatedRepos().isEmpty());
	}

	@Test
	void prepareProvidersScmManagerDedicatedCreatesTenantAndCentralProviders() {
		Config config = config(Map.of(
			"application", Map.of("namePrefix", "fv40-"),
			"scm", Map.of(
				"scmProviderType", ScmProviderType.SCM_MANAGER,
				"scmManager", Map.of("internal", true),
				"gitlab", Map.of("url", "")
			),
			"multiTenant", Map.of(
				"useDedicatedInstance", true,
				"scmManager", Map.of("url", ""),
				"gitlab", Map.of("url", "")
			)
		));

		ScmManagerProviderMock tenant = new ScmManagerProviderMock();
		tenant.setNamePrefix("fv40-");
		ScmManagerProviderMock central = new ScmManagerProviderMock();
		central.setNamePrefix("fv40-");
		GitHandlerForTests gitHandler = new GitHandlerForTests(tenant, central);

		gitHandler.prepareProviders(context(config));

		assertSame(tenant, gitHandler.getTenant());
		assertSame(central, gitHandler.getCentral());
		assertSame(central, gitHandler.getResourcesScm());
	}

	@Test
	void prepareProvidersScmManagerDedicatedDoesNotCreateRepositories() {
		Config config = config(Map.of(
			"application", Map.of("namePrefix", "fv40-"),
			"scm", Map.of(
				"scmProviderType", ScmProviderType.SCM_MANAGER,
				"scmManager", Map.of("internal", true),
				"gitlab", Map.of("url", "")
			),
			"multiTenant", Map.of(
				"useDedicatedInstance", true,
				"scmManager", Map.of("url", ""),
				"gitlab", Map.of("url", "")
			)
		));

		ScmManagerProviderMock tenant = new ScmManagerProviderMock();
		tenant.setNamePrefix("fv40-");
		ScmManagerProviderMock central = new ScmManagerProviderMock();
		central.setNamePrefix("fv40-");
		GitHandlerForTests gitHandler = new GitHandlerForTests(tenant, central);

		gitHandler.prepareProviders(context(config));

		assertTrue(tenant.getCreatedRepos().isEmpty());
		assertTrue(central.getCreatedRepos().isEmpty());
	}

	// ---------- prepareProviders(): GITLAB -------------------------------------------

	@Test
	void prepareProvidersGitlabDedicatedCreatesTenantAndCentralProviders() throws URISyntaxException {
		Config config = config(Map.of(
			"application", Map.of("namePrefix", "fv40-"),
			"scm", Map.of(
				"scmProviderType", ScmProviderType.GITLAB,
				"gitlab", Map.of(
					"url", "https://gitlab.example.com",
					"password", "pat",
					"parentGroupId", 123
				),
				"scmManager", Map.of("internal", true)
			),
			"multiTenant", Map.of(
				"useDedicatedInstance", true,
				"gitlab", Map.of(
					"url", "https://gitlab.example.com",
					"password", "pat2",
					"parentGroupId", 456
				),
				"scmManager", Map.of("url", "")
			)
		));

		GitlabMock tenant = new GitlabMock();
		tenant.setBase(new URI(config.getScm().getGitlab().getUrl()));
		tenant.setNamePrefix("fv40-");

		GitlabMock central = new GitlabMock();
		central.setBase(new URI(config.getMultiTenant().getGitlab().getUrl()));
		central.setNamePrefix("fv40-");

		GitHandlerForTests gitHandler = new GitHandlerForTests(tenant, central);

		gitHandler.prepareProviders(context(config));

		assertSame(tenant, gitHandler.getTenant());
		assertSame(central, gitHandler.getCentral());
		assertSame(central, gitHandler.getResourcesScm());
		assertSame(tenant, gitHandler.getTenant());
		assertSame(central, gitHandler.getCentral());
		assertSame(central, gitHandler.getResourcesScm());
	}

	@Test
	void prepareProvidersGitlabDedicatedDoesNotCreateRepositories() throws URISyntaxException {
		Config config = config(Map.of(
			"application", Map.of("namePrefix", "fv40-"),
			"scm", Map.of(
				"scmProviderType", ScmProviderType.GITLAB,
				"gitlab", Map.of(
					"url", "https://gitlab.example.com",
					"password", "pat",
					"parentGroupId", 123
				),
				"scmManager", Map.of("internal", true)
			),
			"multiTenant", Map.of(
				"useDedicatedInstance", true,
				"gitlab", Map.of(
					"url", "https://gitlab.example.com",
					"password", "pat2",
					"parentGroupId", 456
				),
				"scmManager", Map.of("url", "")
			)
		));

		GitlabMock tenant = new GitlabMock();
		tenant.setBase(new URI(config.getScm().getGitlab().getUrl()));
		tenant.setNamePrefix("fv40-");

		GitlabMock central = new GitlabMock();
		central.setBase(new URI(config.getMultiTenant().getGitlab().getUrl()));
		central.setNamePrefix("fv40-");

		GitHandlerForTests gitHandler = new GitHandlerForTests(tenant, central);

		gitHandler.prepareProviders(context(config));

		assertTrue(tenant.getCreatedRepos().isEmpty());
		assertTrue(central.getCreatedRepos().isEmpty());
	}
}
