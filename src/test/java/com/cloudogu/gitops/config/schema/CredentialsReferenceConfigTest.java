package com.cloudogu.gitops.config.schema;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.ScmCentralSchema;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsReferenceConfigTest {

	@Test
	void copyConstructorKeepsOnlyReferenceMetadata() {
		Credentials original = new Credentials(
			"resolved-user",
			"resolved-password",
			"tool-credentials",
			"gop-job",
			"custom-user",
			"custom-password"
		);

		Credentials copy = new Credentials(original);

		assertThat(copy.getUsername()).isNull();
		assertThat(copy.getPassword()).isNull();
		assertThat(copy.getSecretName()).isEqualTo("tool-credentials");
		assertThat(copy.getSecretNamespace()).isEqualTo("gop-job");
		assertThat(copy.getUsernameKey()).isEqualTo("custom-user");
		assertThat(copy.getPasswordKey()).isEqualTo("custom-password");
	}

	@Test
	void storesSecretReferencesWithoutChangingPlainCredentials() {
		Config config = new Config();
		Credentials reference = secretReference("tool-credentials");

		config.getApplication().setUsername("application-user");
		config.getApplication().setPassword("application-password");
		config.getApplication().setCredentials(reference);

		config.getJenkins().setUsername("jenkins-user");
		config.getJenkins().setPassword("jenkins-password");
		config.getJenkins().setCredentials(reference);

		config.getRegistry().setUsername("registry-user");
		config.getRegistry().setPassword("registry-password");
		config.getRegistry().setCredentials(reference);

		assertThat(config.getApplication().getCredentials()).isSameAs(reference);
		assertThat(config.getApplication().getUsername()).isEqualTo("application-user");
		assertThat(config.getApplication().getPassword()).isEqualTo("application-password");
		assertThat(config.getJenkins().getCredentials()).isSameAs(reference);
		assertThat(config.getJenkins().getUsername()).isEqualTo("jenkins-user");
		assertThat(config.getJenkins().getPassword()).isEqualTo("jenkins-password");
		assertThat(config.getRegistry().getCredentials()).isSameAs(reference);
		assertThat(config.getRegistry().getUsername()).isEqualTo("registry-user");
		assertThat(config.getRegistry().getPassword()).isEqualTo("registry-password");
	}

	@Test
	void scmConfigsPreferSecretReferences() {
		Credentials reference = secretReference("scm-credentials");

		ScmTenantSchema.GitlabTenantConfig tenantGitlab = new ScmTenantSchema.GitlabTenantConfig();
		tenantGitlab.setCredentials(reference);
		ScmTenantSchema.ScmManagerTenantConfig tenantScmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		tenantScmManager.setCredentials(reference);
		ScmCentralSchema.GitlabCentralConfig centralGitlab = new ScmCentralSchema.GitlabCentralConfig();
		centralGitlab.setCredentials(reference);
		ScmCentralSchema.ScmManagerCentralConfig centralScmManager = new ScmCentralSchema.ScmManagerCentralConfig();
		centralScmManager.setCredentials(reference);

		assertThat(tenantGitlab.getCredentials()).isSameAs(reference);
		assertThat(tenantScmManager.getCredentials()).isSameAs(reference);
		assertThat(centralGitlab.getCredentials()).isSameAs(reference);
		assertThat(centralScmManager.getCredentials()).isSameAs(reference);
	}

	@Test
	void scmConfigsKeepPlainCredentialsAsFallback() {
		ScmTenantSchema.GitlabTenantConfig gitlab = new ScmTenantSchema.GitlabTenantConfig();
		gitlab.setUsername("gitlab-user");
		gitlab.setPassword("gitlab-token");

		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setUsername("scmm-user");
		scmManager.setPassword("scmm-password");

		assertThat(gitlab.getCredentials().getUsername()).isEqualTo("gitlab-user");
		assertThat(gitlab.getCredentials().getPassword()).isEqualTo("gitlab-token");
		assertThat(scmManager.getCredentials().getUsername()).isEqualTo("scmm-user");
		assertThat(scmManager.getCredentials().getPassword()).isEqualTo("scmm-password");
	}

	private static Credentials secretReference(String secretName) {
		Credentials reference = new Credentials();
		reference.setSecretName(secretName);
		reference.setSecretNamespace("gop-job");
		return reference;
	}
}
