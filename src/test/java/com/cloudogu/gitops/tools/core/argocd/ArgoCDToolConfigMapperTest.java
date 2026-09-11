package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.credentials.CredentialsReference;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArgoCDToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("tenant-a-");
		config.getApplication().setUsername("application-user");
		config.getApplication().setPassword("application-password");
		Credentials applicationCredentials = new Credentials();
		applicationCredentials.setSecretName("argocd-credentials");
		applicationCredentials.setSecretNamespace("gop-job");
		applicationCredentials.setUsernameKey("admin-user");
		applicationCredentials.setPasswordKey("admin-password");
		config.getApplication().setCredentials(applicationCredentials);
		config.getApplication().getNamespaces().setDedicatedNamespaces(new LinkedHashSet<>(List.of(
			"argocd",
			"monitoring"
		)));
		config.getApplication().getNamespaces().setTenantNamespaces(new LinkedHashSet<>(List.of("team-a", "team-b")));
		config.getApplication().setNetpols(true);
		config.getApplication().setClusterAdmin(true);
		config.getApplication().setInsecure(true);
		// Intentionally differs from the DeploymentContext to verify derived values come from the context.
		config.getApplication().setMirrorRepos(false);
		config.getApplication().setOpenshift(false);
		config.getApplication().setSkipCrds(true);
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getArgocd().setNamespace("gitops");
		config.getFeatures().getArgocd().setOperator(true);
		config.getFeatures().getArgocd().setUrl("https://argocd.example.org");
		config.getFeatures().getArgocd().setEmailFrom("argocd@example.org");
		config.getFeatures().getArgocd().setEmailToAdmin("admins@example.org");
		config.getFeatures().getArgocd().setEnv(List.of(Map.of("name", "FIRST", "value", "one")));
		config.getFeatures().getArgocd().setResourceInclusionsCluster("https://cluster.example.org");
		config.getFeatures().getArgocd().setValues(Map.of("server", Map.of("replicas", 2)));
		config.getFeatures().getArgocd().getOidc().setClientId("argocd-client");
		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setIssuer("production-issuer");
		config.getFeatures().getIngress().setActive(true);
		config.getFeatures().getIngress().setIngressNamespace("edge");
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.org");
		config.getFeatures().getMail().setSmtpPort(2525);
		config.getFeatures().getMail().setSmtpUser("smtp-user");
		config.getFeatures().getMail().setSmtpPassword("smtp-password");
		config.getFeatures().getMail().setCredentials(
			new Credentials(null, null, "smtp-credentials", "gop-job", "smtp-user", "smtp-password")
		);
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getMonitoring().setNamespace("observability");
		config.getFeatures().getSecrets().setActive(true);
		config.getMultiTenant().setCentralArgocdNamespace("central-gitops");
		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setNamespace("source-control");
		config.getScm().setScmManager(scmManager);
		Config.ContentSchema.HelmReleaseSchema helmRelease = new Config.ContentSchema.HelmReleaseSchema();
		helmRelease.setName("database");
		helmRelease.setChart("postgresql");
		helmRelease.setRepoURL("https://charts.example.org");
		config.getContent().setHelmReleases(List.of(helmRelease));

		ArgoCDToolConfig actual = new ArgoCDToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(ArgoCDToolConfig.builder()
													 .active(true)
													 .namespace("tenant-a-gitops")
													 .username("application-user")
													 .password("application-password")
													 .credentials(new CredentialsReference(
														 "argocd-credentials",
														 "gop-job",
														 "admin-user",
														 "admin-password"
													 ))
													 .operator(true)
													 .activeNamespaces(List.of(
														 "argocd",
														 "monitoring",
														 "team-a",
														 "team-b"
													 ))
													 .smtpUser("smtp-user")
													 .smtpPassword("smtp-password")
													 .smtpCredentials(new CredentialsReference(
														 "smtp-credentials",
														 "gop-job",
														 "smtp-user",
														 "smtp-password"
													 ))
													 .values(Map.of("server", Map.of("replicas", 2)))
													 .multiTenant(true)
													 .netpols(true)
													 .tenantName("tenant-a")
													 .url("https://argocd.example.org")
													 .tenantNamespaces(List.of("team-a", "team-b"))
													 .centralNamespace("central-gitops")
													 .clusterAdmin(true)
													 .scmProviderType(ScmProviderType.SCM_MANAGER)
													 .templateConfig(Map.of(
														 "application",
														 Map.of(
															 "clusterAdmin", true,
															 "insecure", true,
															 "mirrorRepos", true,
															 "namePrefix", "tenant-a-",
															 "netpols", true,
															 "openshift", true,
															 "skipCrds", true
														 ),
														 "content",
														 Map.of(
															 "helmReleases",
															 List.of(Map.of("repoURL", "https://charts.example.org"))
														 ),
														 "features",
														 Map.of(
															 "argocd",
															 Map.of(
																 "emailFrom",
																 "argocd@example.org",
																 "emailToAdmin",
																 "admins@example.org",
																 "env",
																 List.of(Map.of("name", "FIRST", "value", "one")),
																 "namespace",
																 "gitops",
																 "oidc",
																 Map.of(
																	 "providerName", "Keycloak",
																	 "issuerUrl", "",
																	 "clientId", "argocd-client",
																	 "clientSecret", "",
																	 "scopes", List.of("openid", "profile", "email"),
																	 "adminGroupName", "",
																	 "enabled", false
																 ),
																 "operator",
																 true,
																 "resourceInclusionsCluster",
																 "https://cluster.example.org",
																 "url",
																 "https://argocd.example.org"
															 ),
															 "certManager",
															 Map.of("active", true, "issuer", "production-issuer"),
															 "ingress",
															 Map.of("active", true, "namespace", "edge"),
															 "mail",
															 Map.of(
																 "active", true,
																 "smtpAddress", "smtp.example.org",
																 "smtpPasswordConfigured", true,
																 "smtpPort", 2525,
																 "smtpUserConfigured", true
															 ),
															 "monitoring",
															 Map.of("active", true, "namespace", "observability"),
															 "secrets",
															 Map.of("active", true)
														 ),
														 "multiTenant",
														 Map.of("centralArgocdNamespace", "central-gitops"),
														 "scm",
														 Map.of(
															 "scmManager", Map.of("namespace", "source-control"),
															 "scmProviderType", ScmProviderType.SCM_MANAGER
														 )
													 ))
													 .rbacTemplateConfig(Map.of(
														 "application", Map.of("openshift", true),
														 "features", Map.of(
															 "monitoring", Map.of("active", true),
															 "secrets", Map.of("active", true)
														 )
													 ))
													 .build());
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.MULTI_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.INTERNAL,
			true,
			DeploymentContext.ClusterDistribution.OPENSHIFT
		);
	}
}
