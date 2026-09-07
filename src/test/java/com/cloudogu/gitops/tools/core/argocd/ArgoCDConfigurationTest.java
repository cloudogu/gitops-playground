package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.config.Config;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

@EnableKubernetesMockClient(crud = true)
class ArgoCDConfigurationTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
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
}
