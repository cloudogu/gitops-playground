package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.CommandExecutorForTest;
import com.cloudogu.gitops.utils.K8sClientForTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static uk.org.webcompere.systemstubs.SystemStubs.withEnvironmentVariable;

@EnableKubernetesMockClient(crud = true)
class ArgoCDConfigurationTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<Map<String, Object>>> YAML_MAP_LIST_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	private final Config config = Config.fromMap(map(
		"application", map(
			"openshift", false,
			"insecure", false,
			"password", "123",
			"username", "something",
			"namePrefix", "",
			"namePrefixForEnvVars", "",
			"gitName", "Cloudogu",
			"gitEmail", "hello@cloudogu.com",
			"namespaces", map(
				"dedicatedNamespaces", List.of("argocd", "monitoring", "traefik", "secrets"),
				"tenantNamespaces", List.of("example-apps-staging", "example-apps-production")
			)
		),
		"scm", map(
			"scmManager", map("internal", true),
			"gitlab", map("url", "")
		),
		"multiTenant", map(
			"scmManager", map("url", ""),
			"gitlab", map("url", ""),
			"useDedicatedInstance", false,
			"centralArgocdNamespace", "argocd"
		),
		"content", map(
			"repos", List.of(
				map(
					"url", "https://github.com/cloudogu/gitops-build-lib",
					"target", "3rd-party-dependencies/gitops-build-lib",
					"overwriteMode", "RESET"
				),
				map(
					"url", "https://github.com/cloudogu/ces-build-lib",
					"target", "3rd-party-dependencies/ces-build-lib",
					"overwriteMode", "RESET"
				),
				map(
					"url", "https://github.com/cloudogu/spring-boot-helm-chart",
					"target", "3rd-party-dependencies/spring-boot-helm-chart",
					"overwriteMode", "RESET"
				),
				map(
					"url", "https://github.com/cloudogu/spring-petclinic",
					"target", "argocd/petclinic-plain",
					"ref", "feature/gitops_ready",
					"targetRef", "main",
					"overwriteMode", "UPGRADE",
					"createJenkinsJob", true
				),
				map(
					"url", "https://github.com/cloudogu/spring-petclinic",
					"target", "argocd/petclinic-helm",
					"ref", "feature/gitops_ready",
					"targetRef", "main",
					"overwriteMode", "UPGRADE",
					"createJenkinsJob", true
				),
				map(
					"url", "https://github.com/cloudogu/gitops-playground",
					"path", "example-apps-via-content-loader/",
					"ref", "main",
					"templating", true,
					"type", "FOLDER_BASED",
					"overwriteMode", "UPGRADE"
				)
			),
			"namespaces", List.of("example-apps-production", "example-apps-staging"),
			"variables", map(
				"petclinic", map("baseDomain", "petclinic.localhost"),
				"images", map(
					"kubectl", "alpine/kubectl:1.35.0",
					"helm", "ghcr.io/cloudogu/helm:4.2.1-1",
					"kubeval", "ghcr.io/cloudogu/helm:4.2.1-1",
					"helmKubeval", "ghcr.io/cloudogu/helm:4.2.1-1",
					"yamllint", "cytopia/yamllint:1.25-0.7",
					"petclinic", "eclipse-temurin:17-jre-alpine",
					"maven", ""
				)
			)
		),
		"features", map(
			"argocd", map(
				"operator", false,
				"active", true,
				"configOnly", true,
				"emailFrom", "argocd@example.org",
				"emailToUser", "app-team@example.org",
				"emailToAdmin", "infra@example.org",
				"resourceInclusionsCluster", ""
			),
			"monitoring", map(
				"active", true,
				"helm", map("chart", "kube-prometheus-stack", "version", "42.0.3")
			),
			"ingress", map("active", true),
			"secrets", map("active", true)
		)
	));

	KubernetesClient client;
	private K8sClient k8sClient;
	private final CommandExecutorForTest helmCommands = new CommandExecutorForTest();
	private ArgoCDRepoLayout clusterResourcesRepoLayout;

	@BeforeEach
	void setupKubernetesClient() {
		ArgoCDK8sClientForTest clientForTest = new ArgoCDK8sClientForTest();
		clientForTest.configure(client);
		k8sClient = spy(clientForTest);

		// no need to wait in tests, we stub!
		doNothing().when(k8sClient).waitForResourcePhase(
			any(String.class),
			any(String.class),
			any(String.class),
			any(String.class)
		);
	}

	@Test
	void configuresArgoCdUrlAndAdditionalRedirectUrls() throws IOException {
		config.getFeatures().getArgocd().setUrl("https://argocd.localhost");

		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> cm = mapValue(
			parseActualYaml(actualHelmValuesFile()),
			"argo-cd",
			"configs",
			"cm"
		);
		assertThat(cm.get("url")).isEqualTo("https://argocd.localhost");
		assertThat((String) cm.get("additionalUrls"))
			.contains("http://argocd.localhost", "https://argocd.localhost");
	}

	@Test
	void configuresArgoCdOidcFromStructuredConfig() throws IOException {
		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setClientId("argocd");
		oidc.setClientSecret("argocd-secret");
		oidc.setAdminGroupName("gop-admins");
		config.getFeatures().getArgocd().setOidc(oidc);

		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> valuesYaml = mapValue(parseActualYaml(actualHelmValuesFile()), "argo-cd", "configs");
		Map<String, Object> oidcConfig = parseYaml((String) value(valuesYaml, "cm", "oidc.config"));
		assertThat(oidcConfig.get("issuer")).isEqualTo("http://keycloak.local.gd/realms/gop");
		assertThat(oidcConfig.get("clientID")).isEqualTo("argocd");
		assertThat((String) value(valuesYaml, "rbac", "policy.csv")).contains("g, gop-admins, role:admin");
		assertThat(value(valuesYaml, "rbac", "scopes")).isEqualTo("[groups]");
	}

	@Test
	void doesNotIncludeOidcConfigurationWhenArgoCdOidcConfigIsNull() throws IOException {
		config.getFeatures().getArgocd().setOidc(null);

		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> valuesYaml = mapValue(parseActualYaml(actualHelmValuesFile()), "argo-cd", "configs");
		assertThat(value(valuesYaml, "cm", "oidc.config")).isNull();
		assertThat(valuesYaml.get("rbac")).isNull();
	}

	@Test
	void usesDefaultScopesWhenArgoCdOidcScopesAreNull() throws IOException {
		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setClientId("argocd");
		oidc.setClientSecret("argocd-secret");
		oidc.setScopes(null);
		config.getFeatures().getArgocd().setOidc(oidc);

		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> valuesYaml = mapValue(parseActualYaml(actualHelmValuesFile()), "argo-cd", "configs");
		Map<String, Object> oidcConfig = parseYaml((String) value(valuesYaml, "cm", "oidc.config"));
		assertThat((List<String>) oidcConfig.get("requestedScopes"))
			.containsExactly("openid", "profile", "email");
	}

	@Test
	void doesNotIncludeMailConfigurationWhenMailServerIsDisabled() throws IOException {
		config.getFeatures().getMail().setActive(false);

		Map<String, Object> valuesYaml = executeAndReadHelmValues();

		assertThat(value(valuesYaml, "argo-cd", "notifications", "enabled")).isEqualTo(false);
		assertThat(value(valuesYaml, "argo-cd", "notifications", "notifiers")).isNull();
	}

	@Test
	void includesMailConfigurationWhenMailServerIsEnabled() throws IOException {
		config.getFeatures().getMail().setActive(true);

		Map<String, Object> valuesYaml = executeAndReadHelmValues();

		assertThat(value(valuesYaml, "argo-cd", "notifications", "enabled")).isEqualTo(true);
		assertThat(value(valuesYaml, "argo-cd", "notifications", "notifiers")).isNotNull();
	}

	@Test
	void includesConfiguredEmailAddresses() throws IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getArgocd().setEmailFrom("argocd@example.com");
		config.getFeatures().getArgocd().setEmailToUser("app-team@example.com");
		config.getFeatures().getArgocd().setEmailToAdmin("argocd@example.com");

		Map<String, Object> valuesYaml = executeAndReadHelmValues();
		Map<String, Object> clusterResourcesYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.projectsDir(), "cluster-resources.yaml").toString()
		);
		Map<String, Object> argocdYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.applicationsDir(), "argocd.yaml").toString()
		);
		Map<String, Object> defaultYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.projectsDir(), "default.yaml").toString()
		);
		Map<String, Object> serviceEmail = parseYaml(
			(String) value(valuesYaml, "argo-cd", "notifications", "notifiers", "service.email")
		);

		assertThat(serviceEmail.get("from")).isEqualTo("argocd@example.com");
		assertThat(value(clusterResourcesYaml, "metadata", "annotations",
			"notifications.argoproj.io/subscribe.email")).isEqualTo("argocd@example.com");
		assertThat(value(argocdYaml, "metadata", "annotations",
			"notifications.argoproj.io/subscribe.on-sync-status-unknown.email"))
			.isEqualTo("argocd@example.com");
		assertThat(value(defaultYaml, "metadata", "annotations",
			"notifications.argoproj.io/subscribe.email")).isEqualTo("argocd@example.com");
	}

	@Test
	void usesDefaultEmailAddressesWhenNoneAreConfigured() throws IOException {
		config.getFeatures().getMail().setActive(true);

		Map<String, Object> valuesYaml = executeAndReadHelmValues();
		Map<String, Object> clusterResourcesYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.projectsDir(), "cluster-resources.yaml").toString()
		);
		Map<String, Object> argocdYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.applicationsDir(), "argocd.yaml").toString()
		);
		Map<String, Object> defaultYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.projectsDir(), "default.yaml").toString()
		);
		Map<String, Object> serviceEmail = parseYaml(
			(String) value(valuesYaml, "argo-cd", "notifications", "notifiers", "service.email")
		);

		assertThat(serviceEmail.get("from")).isEqualTo("argocd@example.org");
		assertThat(value(clusterResourcesYaml, "metadata", "annotations",
			"notifications.argoproj.io/subscribe.email")).isEqualTo("infra@example.org");
		assertThat(value(argocdYaml, "metadata", "annotations",
			"notifications.argoproj.io/subscribe.on-sync-status-unknown.email"))
			.isEqualTo("infra@example.org");
		assertThat(value(defaultYaml, "metadata", "annotations",
			"notifications.argoproj.io/subscribe.email")).isEqualTo("infra@example.org");
	}

	@Test
	void configuresExternalMailServer() throws IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpPort(1010110);
		config.getFeatures().getMail().setSmtpUser("argo@example.com");
		config.getFeatures().getMail().setSmtpPassword("1101:ABCabc&/+*~");

		Map<String, Object> valuesYaml = executeAndReadHelmValues();
		Map<String, Object> serviceEmail = parseYaml(
			(String) value(valuesYaml, "argo-cd", "notifications", "notifiers", "service.email")
		);

		assertThat(serviceEmail.get("host")).isEqualTo(config.getFeatures().getMail().getSmtpAddress());
		assertThat(serviceEmail.get("port")).isEqualTo(config.getFeatures().getMail().getSmtpPort());
		assertThat(serviceEmail.get("username")).isEqualTo("$email-username");
		assertThat(serviceEmail.get("password")).isEqualTo("$email-password");

		Secret mailSecret = client.secrets()
			.inNamespace("argocd")
			.withName("argocd-notifications-secret")
			.get();

		assertThat(mailSecret).isNotNull();
		assertThat(decodedSecretValue(mailSecret, "email-username"))
			.isEqualTo(config.getFeatures().getMail().getSmtpUser());
		assertThat(decodedSecretValue(mailSecret, "email-password"))
			.isEqualTo(config.getFeatures().getMail().getSmtpPassword());
	}

	@Test
	void createsKubernetesSecretWhenExternalMailServerUsernameIsSet() {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpUser("argo@example.com");

		execute(createArgoCD());

		Secret mailSecret = client.secrets()
			.inNamespace("argocd")
			.withName("argocd-notifications-secret")
			.get();

		assertThat(mailSecret).isNotNull();
		assertThat(decodedSecretValue(mailSecret, "email-username"))
			.isEqualTo(config.getFeatures().getMail().getSmtpUser());
	}

	@Test
	void createsKubernetesSecretWhenExternalMailServerPasswordIsSet() {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpPassword("1101:ABCabc&/+*~");

		execute(createArgoCD());

		Secret mailSecret = client.secrets()
			.inNamespace("argocd")
			.withName("argocd-notifications-secret")
			.get();

		assertThat(mailSecret).isNotNull();
		assertThat(decodedSecretValue(mailSecret, "email-password"))
			.isEqualTo(config.getFeatures().getMail().getSmtpPassword());
	}

	@Test
	void configuresExternalMailServerWithoutOptionalValues() throws IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");

		Map<String, Object> valuesYaml = executeAndReadHelmValues();
		Map<String, Object> serviceEmail = parseYaml(
			(String) value(valuesYaml, "argo-cd", "notifications", "notifiers", "service.email")
		);

		assertThat(client.secrets().inNamespace("argocd").withName("argocd-notifications-secret").get()).isNull();
		assertThat(serviceEmail.get("host")).isEqualTo("smtp.example.com");
		assertThat(serviceEmail).doesNotContainKeys("port", "username", "password");
	}

	@Test
	void usesDefaultMailServerWhenNoExternalServerIsSet() throws IOException {
		config.getFeatures().getMail().setActive(true);

		Map<String, Object> valuesYaml = executeAndReadHelmValues();
		Map<String, Object> serviceEmail = parseYaml(
			(String) value(valuesYaml, "argo-cd", "notifications", "notifiers", "service.email")
		);

		assertThat(serviceEmail.get("port")).isEqualTo(1025);
		assertThat(serviceEmail).doesNotHaveToString("username");
		assertThat(serviceEmail).doesNotHaveToString("password");
	}

	@Test
	void rejectsNonStringArgoCdOperatorEnvironmentValues() throws NoSuchFieldException, IllegalAccessException {
		config.getFeatures().getArgocd().setOperator(true);
		Field envField = config.getFeatures().getArgocd().getClass().getDeclaredField("env");
		envField.setAccessible(true);
		envField.set(config.getFeatures().getArgocd(), List.of(map("name", "REPLICAS", "value", 2)));

		assertThatThrownBy(() -> createArgoCD().postConfigInit(config))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid entry found: [name:REPLICAS, value:2]");
	}

	@Test
	void installsArgoCdWithCustomValues() throws IOException {
		config.getFeatures().getArgocd().setValues(map("argo-cd", map("key", "value")));

		Map<String, Object> valuesYaml = executeAndReadHelmValues();

		assertThat(value(valuesYaml, "argo-cd", "key")).isEqualTo("value");
	}

	@Test
	void preparesRepositoriesForAirGappedMode() throws IOException {
		config.getFeatures().getMonitoring().setActive(false);
		config.getApplication().setMirrorRepos(true);

		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> clusterResourcesYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.projectsDir(), "cluster-resources.yaml").toString()
		);
		List<String> sourceRepos = listValue(clusterResourcesYaml, "spec", "sourceRepos");
		assertThat(sourceRepos)
			.contains("http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/"
				+ "kube-prometheus-stack")
			.doesNotContain("https://prometheus-community.github.io/helm-charts");
	}

	@Test
	void generatesArgoCdYamlWithEmptyNamePrefix() throws IOException {
		ArgoCDForTest argocd = (ArgoCDForTest) createArgoCD();
		execute(argocd);

		GitRepo clusterResourcesRepo = argocd.clusterResourcesRepo;
		assertArgoCdYamlPrefixes(
			clusterResourcesRepo.getGitProvider().getUrl(),
			"",
			argocd.getClusterRepoLayout()
		);
	}

	@Test
	void generatesArgoCdYamlWithNamePrefix() throws IOException {
		config.getApplication().setNamePrefix("abc-");

		ArgoCDForTest argocd = (ArgoCDForTest) createArgoCD();
		execute(argocd);

		GitRepo clusterResourcesRepo = argocd.clusterResourcesRepo;
		assertArgoCdYamlPrefixes(
			clusterResourcesRepo.getGitProvider().getUrl(),
			config.getApplication().getNamePrefix(),
			argocd.getClusterRepoLayout()
		);
	}

	@Test
	void skipsCrdsForArgoCd() throws IOException {
		config.getApplication().setSkipCrds(true);

		Map<String, Object> valuesYaml = executeAndReadHelmValues();

		assertThat(value(valuesYaml, "argo-cd", "crds", "install")).isEqualTo(false);
	}

	@Test
	void configuresArgoCdWithActiveNetworkPolicies() throws IOException {
		config.getApplication().setNetpols(true);
		config.getApplication().setNamePrefix("my-prefix-");
		config.getScm().getScmManager().setNamespace("my-prefix-scm-manager");

		Map<String, Object> valuesYaml = executeAndReadHelmValues();
		String argocdValues = Files.readString(
			Path.of(clusterResourcesRepoLayout.argocdRoot(), "argocd", "values.yaml")
		);
		String allowNamespaces = Files.readString(
			Path.of(clusterResourcesRepoLayout.argocdRoot(), "argocd", "templates", "allow-namespaces.yaml")
		);

		assertThat(value(valuesYaml, "argo-cd", "global", "networkPolicy", "create")).isEqualTo(true);
		assertThat(argocdValues).contains("namespace: my-prefix-monitoring");
		assertThat(allowNamespaces)
			.contains("namespace: my-prefix-scm-manager")
			.doesNotContain("namespace: my-prefix-my-prefix-scm-manager")
			.contains("kubernetes.io/metadata.name: my-prefix-argocd");
	}

	@Test
	void setsOperatorServerInsecureToTrueWhenInsecureIsSet() throws IOException {
		config.getApplication().setInsecure(true);
		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		assertThat(value(yaml, "spec", "server", "insecure")).isEqualTo(true);
	}

	@Test
	void setsOperatorCustomValues() throws IOException {
		config.getFeatures().getArgocd().setValues(map("spec", map("key", "value")));
		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		assertThat(value(yaml, "spec", "key")).isEqualTo("value");
	}

	@Test
	void setsOperatorArgoCdUrlAndAdditionalRedirectUrls() throws IOException {
		config.getFeatures().getArgocd().setUrl("https://argocd.localhost");
		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		Map<String, Object> extraConfig = mapValue(yaml, "spec", "extraConfig");
		assertThat(extraConfig.get("url")).isEqualTo("https://argocd.localhost");
		assertThat((String) extraConfig.get("additionalUrls"))
			.contains("http://argocd.localhost", "https://argocd.localhost");
	}

	@Test
	void setsOperatorServerInsecureToFalseWhenInsecureIsNotSet() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		assertThat(value(yaml, "spec", "server", "insecure")).isEqualTo(false);
	}

	@Test
	void generatesIngressWithExpectedHostWhenInsecureAndNotOnOpenShift() throws IOException {
		config.getApplication().setInsecure(true);
		config.getFeatures().getArgocd().setUrl("http://argocd.localhost");
		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		File ingressFile = new File(clusterResourcesRepoLayout.operatorDir(), "ingress.yaml");
		assertThat(ingressFile)
			.as("Ingress file should be generated for insecure mode on non-OpenShift")
			.exists();

		Map<String, Object> ingressYaml = parseActualYaml(ingressFile.toString());
		List<Map<String, Object>> rules = mapListValue(ingressYaml, "spec", "rules");
		assertThat((String) rules.get(0).get("host"))
			.as("Ingress host should match configured ArgoCD hostname")
			.isEqualTo(URI.create(config.getFeatures().getArgocd().getUrl()).getHost());
	}

	@Test
	void doesNotGenerateIngressWhenInsecureIsFalse() {
		config.getApplication().setInsecure(false);
		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		File ingressFile = new File(clusterResourcesRepoLayout.operatorDir(), "ingress.yaml");
		assertThat(ingressFile)
			.as("Ingress file should not be generated when insecure is false")
			.doesNotExist();
	}

	@Test
	void doesNotGenerateIngressOnOpenShift() {
		config.getApplication().setInsecure(true);
		ArgoCD argocd = setupOperatorTest(true);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		File ingressFile = new File(clusterResourcesRepoLayout.operatorDir(), "ingress.yaml");
		assertThat(ingressFile)
			.as("Ingress file should not be generated on OpenShift")
			.doesNotExist();
	}

	@Test
	void doesNotGenerateIngressWhenInsecureIsFalseAndOpenShiftIsTrue() {
		config.getApplication().setInsecure(false);
		ArgoCD argocd = setupOperatorTest(true);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		File ingressFile = new File(clusterResourcesRepoLayout.operatorDir(), "ingress.yaml");
		assertThat(ingressFile)
			.as("Ingress file should not be generated when both flags are false")
			.doesNotExist();
	}

	@Test
	void includesMonitoringAndExternalSecretsResourceInclusionsWhenFeaturesAreActive() throws IOException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getSecrets().setActive(true);

		String expectedMonitoring = "monitoring.coreos.com";
		String expectedExternalSecret = "external-secrets.io";

		ArgoCD argocd = setupOperatorTest(true);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		String resourceInclusions = (String) value(yaml, "spec", "resourceInclusions");

		assertThat(resourceInclusions).contains(expectedMonitoring, expectedExternalSecret);
	}

	@Test
	void excludesMonitoringAndExternalSecretsResourceInclusionsWhenFeaturesAreInactive() throws IOException {
		config.getFeatures().getMonitoring().setActive(false);
		config.getFeatures().getSecrets().setActive(false);

		String expectedMonitoring = "monitoring.coreos.com";
		String expectedExternalSecret = "external-secrets.io";

		ArgoCD argocd = setupOperatorTest(true);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		String resourceInclusions = (String) value(yaml, "spec", "resourceInclusions");

		assertThat(resourceInclusions).doesNotContain(expectedMonitoring, expectedExternalSecret);
	}

	@Test
	void configuresResourceInclusionsCluster() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);
		config.getFeatures().getArgocd().setResourceInclusionsCluster("https://192.168.0.1:6443");

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		String expectedClusterUrl = "https://192.168.0.1:6443";
		String resourceInclusions = (String) value(yaml, "spec", "resourceInclusions");
		List<Map<String, Object>> parsedResourceInclusions = parseYamlList(resourceInclusions);

		for (Map<String, Object> resource : parsedResourceInclusions) {
			assertThat(resource).containsKey("clusters");
			assertThat(listValue(resource, "clusters")).contains(expectedClusterUrl);
		}
	}

	@Test
	void resourceInclusionsClusterFromConfigTrumpsEnvironmentVariables() throws Exception {
		ArgoCD argocd = setupOperatorTest(false);
		config.getApplication().setInternalKubernetesApiUrl("https://192.168.0.1:6443");

		withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "100.125.0.1")
			.and("KUBERNETES_SERVICE_PORT", "443")
			.execute(() -> execute(argocd));

		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		String expectedClusterUrlFromConfig = "https://192.168.0.1:6443";
		String resourceInclusions = (String) value(yaml, "spec", "resourceInclusions");
		List<Map<String, Object>> parsedResourceInclusions = parseYamlList(resourceInclusions);

		for (Map<String, Object> resource : parsedResourceInclusions) {
			assertThat(resource).containsKey("clusters");
			assertThat(listValue(resource, "clusters"))
				.contains(expectedClusterUrlFromConfig)
				.doesNotContain("https://100.125.0.1:443");
		}
	}

	@Test
	void setsEnvironmentVariablesInArgoCdComponentsWhenProvided() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);
		config.getFeatures().getArgocd().setEnv(List.of(
			Map.of("name", "ENV_VAR_1", "value", "value1"),
			Map.of("name", "ENV_VAR_2", "value", "value2")
		));

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		List<Map<String, Object>> expectedEnv = List.of(
			map("name", "ENV_VAR_1", "value", "value1"),
			map("name", "ENV_VAR_2", "value", "value2")
		);

		assertThat(value(yaml, "spec", "applicationSet", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "notifications", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "controller", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "repo", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "server", "env")).isEqualTo(expectedEnv);
	}

	@Test
	void doesNotSetEnvironmentVariablesWhenNoneAreProvided() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);
		config.getFeatures().getArgocd().setEnv(List.of());

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());

		assertThat(mapValue(yaml, "spec", "applicationSet")).doesNotContainKey("env");
		assertThat(mapValue(yaml, "spec", "notifications")).doesNotContainKey("env");
		assertThat(mapValue(yaml, "spec", "controller")).doesNotContainKey("env");
		assertThat(mapValue(yaml, "spec", "redis")).doesNotContainKey("env");
		assertThat(mapValue(yaml, "spec", "repo")).doesNotContainKey("env");
		assertThat(mapValue(yaml, "spec", "server")).doesNotContainKey("env");
	}

	@Test
	void setsSingleEnvironmentVariableInArgoCdComponentsWhenProvided() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);
		config.getFeatures().getArgocd().setEnv(List.of(
			Map.of("name", "ENV_VAR_SINGLE", "value", "singleValue")
		));

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Map<String, Object> yaml = parseActualYaml(clusterResourcesRepoLayout.operatorConfigFile());
		List<Map<String, Object>> expectedEnv = List.of(
			map("name", "ENV_VAR_SINGLE", "value", "singleValue")
		);

		assertThat(value(yaml, "spec", "applicationSet", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "notifications", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "controller", "env")).isEqualTo(expectedEnv);
		assertThat(value(yaml, "spec", "server", "env")).isEqualTo(expectedEnv);
	}

	@Test
	void preparesArgoCdRepoWithOperatorConfigurationFile() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Path argocdConfigPath = Path.of(clusterResourcesRepoLayout.operatorConfigFile());
		Path rbacConfigPath = Path.of(clusterResourcesRepoLayout.operatorRbacDir());

		assertThat(argocdConfigPath.toFile()).exists();
		assertThat(rbacConfigPath.toFile()).exists();

		Map<String, Object> yaml = parseActualYaml(argocdConfigPath.toString());
		assertThat(yaml.get("apiVersion")).isEqualTo("argoproj.io/v1beta1");
		assertThat(yaml.get("kind")).isEqualTo("ArgoCD");
	}

	@Test
	void doesNotCreateOperatorFilesWhenOperatorIsDisabled() {
		ArgoCD argocd = createArgoCD();

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Path argocdConfigPath = Path.of(clusterResourcesRepoLayout.operatorConfigFile());
		Path rbacConfigPath = Path.of(clusterResourcesRepoLayout.operatorRbacDir());

		assertThat(argocdConfigPath.toFile()).doesNotExist();
		assertThat(rbacConfigPath.toFile()).doesNotExist();
	}

	@Test
	void deploysWithOperatorWithoutOpenShiftConfiguration() throws IOException {
		ArgoCD argocd = setupOperatorTest(false);

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();
		Path argocdConfigPath = Path.of(clusterResourcesRepoLayout.operatorConfigFile());

		assertThat(argocdConfigPath.toFile()).exists();

		Map<String, Object> yaml = parseActualYaml(argocdConfigPath.toString());
		assertThat(value(yaml, "spec", "rbac")).isNull();
		assertThat(value(yaml, "spec", "sso")).isNull();

		Map<String, Object> argocdYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.applicationsDir(), "argocd.yaml").toString()
		);
		assertThat(value(argocdYaml, "spec", "source", "directory", "recurse")).isEqualTo(true);
		assertThat(value(argocdYaml, "spec", "source", "path")).isEqualTo("apps/argocd/operator/");
	}

	@Test
	void generatesOperatorRbacsFromRbacDefinitions() throws IOException {
		config.getApplication().setNamePrefix("testPrefix-");

		List<String> expectedNamespaces = List.of(
			"testPrefix-monitoring",
			"testPrefix-secrets",
			"testPrefix-traefik",
			"testPrefix-example-apps-staging",
			"testPrefix-example-apps-production"
		);

		config.getApplication().getNamespaces().setDedicatedNamespaces(new LinkedHashSet<>(List.of(
			"monitoring",
			"secrets",
			"traefik",
			"example-apps-staging",
			"example-apps-production"
		)));

		ArgoCD argocd = setupOperatorTest(false);
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		File rbacPath = Path.of(clusterResourcesRepoLayout.operatorRbacDir()).toFile();

		for (String namespace : expectedNamespaces) {
			File roleFile = new File(rbacPath, "role-argocd-" + namespace + ".yaml");
			File bindingFile = new File(rbacPath, "rolebinding-argocd-" + namespace + ".yaml");

			assertThat(roleFile).exists();
			assertThat(bindingFile).exists();

			Map<String, Object> roleYaml = parseActualYaml(roleFile.toString());
			Map<String, Object> bindingYaml = parseActualYaml(bindingFile.toString());

			assertThat(roleYaml.get("kind")).isEqualTo("Role");
			assertThat(value(roleYaml, "metadata", "name")).isEqualTo("argocd");
			assertThat(value(roleYaml, "metadata", "namespace")).isEqualTo(namespace);

			assertThat(bindingYaml.get("kind")).isEqualTo("RoleBinding");
			assertThat(value(bindingYaml, "metadata", "name")).isEqualTo("argocd");
			assertThat(value(bindingYaml, "metadata", "namespace")).isEqualTo(namespace);

			List<Map<String, Object>> subjects = mapListValue(bindingYaml, "subjects");
			assertThat(subjects).isNotEmpty();
			assertThat(subjects.stream().map(subject -> subject.get("kind")).toList())
				.containsOnly("ServiceAccount");
			assertThat(subjects.stream().map(subject -> subject.get("namespace")).toList())
				.containsOnly("testPrefix-argocd");
			assertThat(subjects.stream().map(subject -> subject.get("name")).toList())
				.containsExactlyInAnyOrder(
					"argocd-argocd-server",
					"argocd-argocd-application-controller",
					"argocd-applicationset-controller"
				);

			Map<String, Object> roleRef = mapValue(bindingYaml, "roleRef");
			assertThat(roleRef).isNotNull();
			assertThat(roleRef.get("name")).isEqualTo("argocd");
			assertThat(roleRef.get("kind")).isEqualTo("Role");
		}
	}

	@Test
	void deploysWithOperatorWithOpenShiftConfiguration() throws IOException {
		ArgoCD argocd = setupOperatorTest(true);

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		Path argocdConfigPath = Path.of(clusterResourcesRepoLayout.operatorConfigFile());
		assertThat(argocdConfigPath.toFile()).exists();

		Map<String, Object> yaml = parseActualYaml(argocdConfigPath.toString());
		assertThat(value(yaml, "spec", "sso")).isNotNull();
		assertThat(value(yaml, "spec", "sso", "dex", "openShiftOAuth")).isEqualTo(true);
		assertThat(value(yaml, "spec", "sso", "provider")).isEqualTo("dex");
		assertThat(value(yaml, "spec", "rbac")).isNotNull();
		assertThat(value(yaml, "spec", "server", "route", "enabled")).isEqualTo(true);
	}

	@Test
	void createsAllNecessaryNamespaces() {
		ArgoCD argocd = createArgoCD();

		execute(argocd);

		for (String namespace : config.getApplication().getNamespaces().getActiveNamespaces()) {
			assertThat(client.namespaces().withName(namespace).get()).isNotNull();
		}
	}

	@Test
	void doesNotGenerateCentralBootstrapIngressWhenInsecureIsFalseInDedicatedMode() {
		setupDedicatedInstanceMode();

		assertThat(clusterResourcesRepoLayout).isNotNull();

		File ingressFile = new File(clusterResourcesRepoLayout.operatorDir(), "ingress.yaml");
		assertThat(ingressFile)
			.as("Ingress file should not be generated when insecure is false")
			.doesNotExist();
	}

	@Test
	void dedicatedModeAppliesCentralAndTenantBootstrapResources() {
		config.getApplication().setNamePrefix("testPrefix-");
		config.getMultiTenant().getScmManager().setUrl("scmm.testhost/scm");
		config.getMultiTenant().getScmManager().setUsername("testUserName");
		config.getMultiTenant().getScmManager().setPassword("testPassword");
		config.getMultiTenant().setUseDedicatedInstance(true);
		config.getFeatures().getArgocd().setOperator(true);
		config.getFeatures().getArgocd().setResourceInclusionsCluster("https://192.168.0.1:6443");

		doReturn("Applied").when(k8sClient).applyYaml(any(String.class));

		ArgoCD argocd = createArgoCD();
		execute(argocd);

		ArgoCDForTest argoCDForTest = (ArgoCDForTest) argocd;
		ArgoCDRepoLayout clusterLayout = argoCDForTest.getClusterRepoLayout();
		ArgoCDRepoLayout tenantLayout = argoCDForTest.getTenantRepoLayout();

		verify(k8sClient).applyYaml(Path.of(clusterLayout.projectsDir(), "tenant.yaml").toString());
		verify(k8sClient).applyYaml(Path.of(clusterLayout.applicationsDir(), "bootstrap.yaml").toString());
		verify(k8sClient).applyYaml(Path.of(tenantLayout.projectsDir(), "argocd.yaml").toString());
		verify(k8sClient).applyYaml(Path.of(tenantLayout.applicationsDir(), "bootstrap.yaml").toString());
	}

	@Test
	void dedicatedModeCreatesCentralRepoCredentialsSecret() {
		config.getApplication().setNamePrefix("testPrefix-");
		config.getMultiTenant().getScmManager().setUrl("scmm.testhost/scm");
		config.getMultiTenant().getScmManager().setUsername("testUserName");
		config.getMultiTenant().getScmManager().setPassword("testPassword");
		config.getMultiTenant().setUseDedicatedInstance(true);
		config.getFeatures().getArgocd().setOperator(true);
		config.getFeatures().getArgocd().setResourceInclusionsCluster("https://192.168.0.1:6443");

		doReturn("Applied").when(k8sClient).applyYaml(any(String.class));

		execute(createArgoCD());

		Secret centralRepoCredentialsSecret = client.secrets()
			.inNamespace(config.getMultiTenant().getCentralArgocdNamespace())
			.withName("argocd-repo-creds-central-scm")
			.get();

		assertThat(centralRepoCredentialsSecret).isNotNull();
		assertThat(centralRepoCredentialsSecret.getMetadata().getLabels().get("argocd.argoproj.io/secret-type"))
			.isEqualTo("repo-creds");
	}

	@Test
	void generatesCentralTemplatesForDedicatedInstances() throws IOException {
		setupDedicatedInstanceMode();

		assertThat(clusterResourcesRepoLayout).isNotNull();

		assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + "/applications/argocd.yaml")).exists();
		assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + "/applications/bootstrap.yaml")).exists();
		assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + "/applications/projects.yaml")).exists();
		assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + "/applications/example-apps.yaml")).doesNotExist();

		Map<String, Object> argocdYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.argocdRoot(), "applications/argocd.yaml").toString()
		);
		Map<String, Object> bootstrapYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.argocdRoot(), "applications/bootstrap.yaml").toString()
		);
		Map<String, Object> projectsYaml = parseActualYaml(
			Path.of(clusterResourcesRepoLayout.argocdRoot(), "applications/projects.yaml").toString()
		);

		assertThat(value(argocdYaml, "metadata", "name")).isEqualTo("testPrefix-argocd");
		assertThat(value(argocdYaml, "metadata", "namespace")).isEqualTo("argocd");
		assertThat(value(argocdYaml, "spec", "project")).isEqualTo("testPrefix");
		assertThat(value(argocdYaml, "spec", "source", "path")).isEqualTo("apps/argocd/operator/");

		assertThat(value(bootstrapYaml, "metadata", "name")).isEqualTo("testPrefix-bootstrap");
		assertThat(value(bootstrapYaml, "metadata", "namespace")).isEqualTo("argocd");
		assertThat(value(bootstrapYaml, "spec", "project")).isEqualTo("testPrefix");
		assertThat(value(bootstrapYaml, "spec", "source", "repoURL"))
			.isEqualTo("scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources.git");

		assertThat(value(projectsYaml, "metadata", "name")).isEqualTo("testPrefix-projects");
		assertThat(value(projectsYaml, "metadata", "namespace")).isEqualTo("argocd");
		assertThat(value(projectsYaml, "spec", "project")).isEqualTo("testPrefix");

		File tenantProjectFile = new File(clusterResourcesRepoLayout.argocdRoot() + "/projects/tenant.yaml");
		assertThat(tenantProjectFile).exists();

		Map<String, Object> tenantProject = parseActualYaml(tenantProjectFile.toString());
		assertThat(value(tenantProject, "metadata", "name")).isEqualTo("testPrefix");
		assertThat(value(tenantProject, "metadata", "namespace")).isEqualTo("argocd");
		assertThat(listValue(tenantProject, "spec", "sourceRepos"))
			.first()
			.isEqualTo("scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources.git");
	}

	@Test
	void appendsNamespacesToDefaultClusterConfigSecret() {
		config.getApplication().getNamespaces().setDedicatedNamespaces(new LinkedHashSet<>(List.of(
			"dedi-test1",
			"dedi-test2",
			"dedi-test3"
		)));
		config.getApplication().getNamespaces().setTenantNamespaces(new LinkedHashSet<>(List.of(
			"tenant-test1",
			"tenant-test2",
			"tenant-test3"
		)));

		setupDedicatedInstanceMode();

		Secret defaultClusterConfig = client.secrets()
			.inNamespace("argocd")
			.withName("argocd-default-cluster-config")
			.get();

		assertThat(defaultClusterConfig).isNotNull();

		String namespaces = decodedSecretValue(defaultClusterConfig, "namespaces");
		assertThat(namespaces)
			.contains("testnamespace1")
			.contains("testnamespace2")
			.contains("testPrefix-dedi-test1")
			.contains("testPrefix-dedi-test2")
			.contains("testPrefix-dedi-test3")
			.contains("testPrefix-tenant-test1")
			.contains("testPrefix-tenant-test2")
			.contains("testPrefix-tenant-test3");
	}

	@Test
	void removesMultiTenantFolderWhenDedicatedModeIsDisabled() {
		config.getMultiTenant().setUseDedicatedInstance(false);

		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();

		assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), "multiTenant/")).doesNotExist();
		assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), "applications/")).exists();
		assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), "projects/")).exists();
	}

	@Test
	void removesUnusedMultiTenantFolderInDedicatedMode() {
		setupDedicatedInstanceMode();

		assertThat(clusterResourcesRepoLayout).isNotNull();
		assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), "multiTenant/")).doesNotExist();
		assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), "applications/")).exists();
		assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), "projects/")).exists();
	}

	@Test
	void generatesDedicatedModeRbacs() throws IOException {
		config.getApplication().getNamespaces().setTenantNamespaces(new LinkedHashSet<>(List.of(
			"testprefix-tenant-test1",
			"testprefix-tenant-test2",
			"testprefix-tenant-test3"
		)));
		setupDedicatedInstanceMode();

		File rbacFolder = new File(clusterResourcesRepoLayout.operatorRbacDir());
		File rbacTenantFolder = new File(clusterResourcesRepoLayout.operatorRbacDir(), "tenant");
		assertThat(rbacFolder).exists();
		assertThat(rbacTenantFolder).exists();

		assertThat(rbacFolder.listFiles(File::isFile)).hasSize(14);
		assertThat(rbacTenantFolder.listFiles(File::isFile)).hasSize(6);

		for (File file : rbacFolder.listFiles()) {
			if (file.getName().startsWith("role-") && file.getName().contains("dedi")) {
				Map<String, Object> rbacFile = parseActualYaml(file.toString());
				assertThat(value(rbacFile, "metadata", "namespace"))
					.isIn(config.getApplication().getNamespaces().getActiveNamespaces());
			}
			if (file.getName().startsWith("rolebinding-") && file.getName().contains("dedi")) {
				Map<String, Object> rbacFile = parseActualYaml(file.toString());
				List<Map<String, Object>> subjects = mapListValue(rbacFile, "subjects");
				assertThat(subjects.stream().map(subject -> subject.get("namespace")).toList())
					.containsExactly("argocd", "argocd", "argocd");
			}
		}

		for (File file : rbacTenantFolder.listFiles()) {
			if (file.getName().startsWith("role-")) {
				Map<String, Object> rbacFile = parseActualYaml(file.toString());
				assertThat(value(rbacFile, "metadata", "namespace"))
					.isIn(config.getApplication().getNamespaces().getTenantNamespaces());
			}

			if (file.getName().startsWith("rolebinding-")) {
				Map<String, Object> rbacFile = parseActualYaml(file.toString());
				List<Map<String, Object>> subjects = mapListValue(rbacFile, "subjects");
				assertThat(subjects.stream().map(subject -> subject.get("namespace")).toList())
					.containsExactly("testPrefix-argocd", "testPrefix-argocd", "testPrefix-argocd");
			}
		}
	}

	private void setupDedicatedInstanceMode() {
		config.getApplication().setNamePrefix("testPrefix-");
		config.getMultiTenant().getScmManager().setUrl("scmm.testhost/scm");
		config.getMultiTenant().getScmManager().setUsername("testUserName");
		config.getMultiTenant().getScmManager().setPassword("testPassword");
		config.getMultiTenant().setUseDedicatedInstance(true);
		ArgoCD argocd = setupOperatorTest(false);

		doReturn("Applied").when(k8sClient).applyYaml(any(String.class));

		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();
	}

	private ArgoCD setupOperatorTest(boolean openshift) {
		config.getFeatures().getArgocd().setOperator(true);
		config.getFeatures().getArgocd().setResourceInclusionsCluster("https://192.168.0.1:6443");
		config.getApplication().setOpenshift(openshift);
		return createArgoCD();
	}

	private void assertArgoCdYamlPrefixes(
		String scmmUrl,
		String expectedPrefix,
		ArgoCDRepoLayout repoLayout) throws IOException {
		assertAllYamlFiles(new File(repoLayout.argocdRoot()), "projects", 3, file -> {
			Map<String, Object> yaml = parseActualYaml(file.toString());
			List<String> sourceRepos = listValue(yaml, "spec", "sourceRepos");

			if (sourceRepos != null) {
				for (String sourceRepo : sourceRepos) {
					if (sourceRepo.startsWith(scmmUrl)) {
						assertThat(sourceRepo)
							.as(file + " sourceRepos have name prefix")
							.startsWith(scmmUrl + "/repo/" + expectedPrefix + "argocd");
					}
				}
			}

			String metadataNamespace = (String) value(yaml, "metadata", "namespace");
			if (metadataNamespace != null && !metadataNamespace.isEmpty()) {
				assertThat(metadataNamespace)
					.as(file + " metadata.namespace has name prefix")
					.isEqualTo(expectedPrefix + "argocd");
			}

			List<String> sourceNamespaces = listValue(yaml, "spec", "sourceNamespaces");
			if (sourceNamespaces != null) {
				for (String sourceNamespace : sourceNamespaces) {
					if (!"*".equals(sourceNamespace)) {
						assertThat(sourceNamespace)
							.as(file + " spec.sourceNamespace has name prefix")
							.startsWith(expectedPrefix);
					}
				}
			}
		});

		assertAllYamlFiles(new File(repoLayout.argocdRoot()), "applications", 3, file -> {
			Map<String, Object> yaml = parseActualYaml(file.toString());
			assertThat((String) value(yaml, "spec", "source", "repoURL"))
				.as(file + " repoURL have name prefix")
				.startsWith(scmmUrl + "/repo/" + expectedPrefix + "argocd");
			assertThat(value(yaml, "metadata", "namespace"))
				.as(file + " metadata.namespace has name prefix")
				.isEqualTo(expectedPrefix + "argocd");
			assertThat(value(yaml, "spec", "destination", "namespace"))
				.as(file + " spec.destination.namespace has name prefix")
				.isEqualTo(expectedPrefix + "argocd");
		});
	}

	private static void assertAllYamlFiles(
		File rootDir,
		String childDir,
		int numberOfFiles,
		PathAssertion assertion) throws IOException {
		Path rootPath = Path.of(rootDir.getAbsolutePath(), childDir);
		List<Path> yamlFiles;
		try (Stream<Path> files = Files.walk(rootPath)) {
			yamlFiles = files
				.filter(Files::isRegularFile)
				.filter(path -> {
					String normalizedPath = path.toString().replace('\\', '/');
					return normalizedPath.endsWith(".yaml") || normalizedPath.endsWith(".yml");
				})
				.toList();
		}

		for (Path yamlFile : yamlFiles) {
			assertion.accept(yamlFile);
		}

		assertThat(yamlFiles).hasSize(numberOfFiles);
	}

	private Map<String, Object> executeAndReadHelmValues() throws IOException {
		ArgoCD argocd = createArgoCD();
		execute(argocd);
		clusterResourcesRepoLayout = ((ArgoCDForTest) argocd).getClusterRepoLayout();
		return parseActualYaml(actualHelmValuesFile());
	}

	private String actualHelmValuesFile() {
		return clusterResourcesRepoLayout.helmDir() + "/values.yaml";
	}

	private ArgoCD createArgoCD() {
		prepareKubernetesObjectsForArgoCd();

		ArgoCDForTest argoCD = ArgoCDForTest.newWithAutoProviders(config, k8sClient, helmCommands);
		return argoCD;
	}

	private boolean execute(ArgoCD argoCD) {
		return ((ArgoCDForTest) argoCD).execute();
	}

	private void prepareKubernetesObjectsForArgoCd() {
		String namePrefix = config.getApplication().getNamePrefix() == null
			? ""
			: config.getApplication().getNamePrefix();
		String configuredNamespace = config.getFeatures().getArgocd().getNamespace();
		String namespace = namePrefix + (configuredNamespace == null || configuredNamespace.isEmpty()
			? "argocd"
			: configuredNamespace);
		String centralNamespace = config.getMultiTenant().getCentralArgocdNamespace() == null
			|| config.getMultiTenant().getCentralArgocdNamespace().isEmpty()
			? "argocd"
			: config.getMultiTenant().getCentralArgocdNamespace();

		createNamespaceIfMissing(namespace);
		createNamespaceIfMissing(centralNamespace);
		createArgoCdCrds();

		config.getApplication().getNamespaces().getActiveNamespaces().forEach(this::createNamespaceIfMissing);

		createSecretIfMissing("argocd-secret", namespace, Map.of());
		createSecretIfMissing("argocd-cluster", namespace, Map.of());
		createSecretIfMissing(
			"argocd-default-cluster-config",
			namespace,
			Map.of("namespaces", encode("testnamespace1,testnamespace2"))
		);

		if (Boolean.TRUE.equals(config.getMultiTenant().getUseDedicatedInstance())) {
			createSecretIfMissing(
				"argocd-default-cluster-config",
				centralNamespace,
				Map.of("namespaces", encode("testnamespace1,testnamespace2"))
			);
		}
	}

	private void createArgoCdCrds() {
		createNamespacedCrd(
			"appprojects.argoproj.io",
			"argoproj.io",
			"v1alpha1",
			"AppProject",
			"appprojects",
			"appproject"
		);
		createNamespacedCrd(
			"applications.argoproj.io",
			"argoproj.io",
			"v1alpha1",
			"Application",
			"applications",
			"application"
		);
		createNamespacedCrd(
			"argocds.argoproj.io",
			"argoproj.io",
			"v1beta1",
			"ArgoCD",
			"argocds",
			"argocd"
		);
	}

	private void createNamespacedCrd(
		String name,
		String group,
		String version,
		String kind,
		String plural,
		String singular) {
		if (client.apiextensions().v1().customResourceDefinitions().withName(name).get() != null) {
			return;
		}

		CustomResourceDefinition crd = new CustomResourceDefinitionBuilder()
			.withNewMetadata()
			.withName(name)
			.endMetadata()
			.withNewSpec()
			.withGroup(group)
			.withScope("Namespaced")
			.withNewNames()
			.withKind(kind)
			.withPlural(plural)
			.withSingular(singular)
			.endNames()
			.addNewVersion()
			.withName(version)
			.withServed(true)
			.withStorage(true)
			.withNewSchema()
			.withNewOpenAPIV3Schema()
			.withType("object")
			.withXKubernetesPreserveUnknownFields(true)
			.endOpenAPIV3Schema()
			.endSchema()
			.endVersion()
			.endSpec()
			.build();

		client.apiextensions().v1().customResourceDefinitions().resource(crd).create();
	}

	private void createNamespaceIfMissing(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException();
		}

		if (client.namespaces().withName(name).get() == null) {
			client.namespaces().resource(new NamespaceBuilder()
				.withNewMetadata()
				.withName(name)
				.endMetadata()
				.build())
				.create();
		}
	}

	private String decodedSecretValue(Secret secret, String key) {
		if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
			return secret.getStringData().get(key);
		}

		if (secret.getData() != null && secret.getData().containsKey(key)) {
			return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
		}

		return null;
	}

	private void createSecretIfMissing(String name, String namespace, Map<String, String> data) {
		if (namespace == null || namespace.isEmpty()) {
			throw new IllegalArgumentException();
		}

		createNamespaceIfMissing(namespace);

		if (client.secrets().inNamespace(namespace).withName(name).get() == null) {
			Secret secret = new SecretBuilder()
				.withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.withType("Opaque")
				.withData(data)
				.build();

			client.secrets().inNamespace(namespace).resource(secret).create();
		}
	}

	private static String encode(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, Object> parseActualYaml(String pathToYamlFile) throws IOException {
		return YAML_MAPPER.readValue(new File(pathToYamlFile), YAML_MAP_TYPE);
	}

	private static Map<String, Object> parseYaml(String yaml) throws IOException {
		return YAML_MAPPER.readValue(yaml, YAML_MAP_TYPE);
	}

	private static List<Map<String, Object>> parseYamlList(String yaml) throws IOException {
		return YAML_MAPPER.readValue(yaml, YAML_MAP_LIST_TYPE);
	}

	private static Object value(Map<String, Object> yaml, String... path) {
		Object current = yaml;
		for (String key : path) {
			if (!(current instanceof Map<?, ?> currentMap)) {
				return null;
			}
			current = currentMap.get(key);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapValue(Map<String, Object> yaml, String... path) {
		return (Map<String, Object>) value(yaml, path);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> mapListValue(Map<String, Object> yaml, String... path) {
		return (List<Map<String, Object>>) value(yaml, path);
	}

	@SuppressWarnings("unchecked")
	private static List<String> listValue(Map<String, Object> yaml, String... path) {
		return (List<String>) value(yaml, path);
	}

	private static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int index = 0; index < keyValues.length; index += 2) {
			result.put((String) keyValues[index], keyValues[index + 1]);
		}
		return result;
	}

	private static class ArgoCDK8sClientForTest extends K8sClientForTest {

		void configure(KubernetesClient client) {
			setClient(client);
			sleepTimeMillis = 1;
			defaultRetries = 1;
		}
	}

	@FunctionalInterface
	private interface PathAssertion {

		void accept(Path path) throws IOException;
	}
}
