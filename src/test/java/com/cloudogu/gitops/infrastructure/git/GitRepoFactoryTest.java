package com.cloudogu.gitops.infrastructure.git;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.utils.FileSystemUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitRepoFactoryTest {

	private final Config config = createConfig();
	private final GitRepoFactory factory = new GitRepoFactory(config, new FileSystemUtils());

	@Test
	void createsRepoWithEmptyNamePrefix() {
		GitRepo repo = factory.create("expectedRepoTarget", new ScmManagerProviderMock());

		assertThat(repo.getRepoTarget()).isEqualTo("expectedRepoTarget");
	}

	@Test
	void createsRepoWithNamePrefix() {
		config.getApplication().setNamePrefix("abc-");

		GitRepo repo = factory.create("expectedRepoTarget", new ScmManagerProviderMock());

		assertThat(repo.getRepoTarget()).isEqualTo("abc-expectedRepoTarget");
	}

	@Test
	void createsRepoWithNamePrefixWhenInNamespaceThirdPartyDependencies() {
		config.getApplication().setNamePrefix("abc-");

		GitRepo repo = factory.create(
			GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES + "/foo",
			new ScmManagerProviderMock()
		);

		assertThat(repo.getRepoTarget()).isEqualTo(
			config.getApplication().getNamePrefix() + GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES + "/foo"
		);
	}

	private static Config createConfig() {
		Config config = new Config();
		config.getApplication().setGitName("Cloudogu");
		config.getApplication().setGitEmail("hello@cloudogu.com");

		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setUsername("dont-care-username");
		scmManager.setPassword("dont-care-password");
		config.getScm().setScmManager(scmManager);

		return config;
	}
}
