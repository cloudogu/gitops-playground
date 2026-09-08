package com.cloudogu.gitops.infrastructure.kubernetes.rbac;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RbacDefinitionTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	private final Config config = Config.fromMap(Map.of(
		"scm", Map.of(
			"scmManager", Map.of(
				"username", "user",
				"password", "pass",
				"url", "http://localhost"
			)
		),
		"application", Map.of(
			"namePrefix", "",
			"insecure", false,
			"gitName", "Test User",
			"gitEmail", "test@example.com"
		)
	));

	private final GitRepo repo = new GitRepo(config, null, "my-repo", new FileSystemUtils());

	@Test
	void generatesAtLeastOneRbacYamlFile() {
		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("access")
			.withNamespace("testing")
			.withServiceAccountsFrom("testing", List.of("reader"))
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac");
		File[] yamlFiles = outputDir.listFiles((FileFilter) file -> file.getName().endsWith(".yaml"));
		List<String> fileNames = Arrays.stream(yamlFiles).map(File::getName).toList();

		assertThat(yamlFiles).isNotEmpty();
		assertThat(fileNames).anyMatch(name -> name.contains("role") || name.contains("rolebinding"));
	}

	@Test
	void failsIfNameIsMissing() {
		IllegalArgumentException ex = assertThrows(
			IllegalArgumentException.class, () ->
				new RbacDefinition(Role.Variant.ARGOCD)
					.withNamespace("testing")
					.withServiceAccountsFrom("testing", List.of("reader"))
					.withRepo(repo)
					.withTemplateConfig(rbacConfig())
					.generate()
		);

		assertThat(ex.getMessage()).contains("name must not be blank");
	}

	@Test
	void failsIfNamespaceIsMissing() {
		IllegalArgumentException ex = assertThrows(
			IllegalArgumentException.class, () ->
				new RbacDefinition(Role.Variant.ARGOCD)
					.withName("access")
					.withServiceAccountsFrom("testing", List.of("reader"))
					.withRepo(repo)
					.withTemplateConfig(rbacConfig())
					.generate()
		);

		assertThat(ex.getMessage()).contains("namespace must not be blank");
	}

	@Test
	void failsIfServiceAccountsAreEmpty() {
		IllegalArgumentException ex = assertThrows(
			IllegalArgumentException.class, () ->
				new RbacDefinition(Role.Variant.ARGOCD)
					.withName("access")
					.withNamespace("testing")
					.withRepo(repo)
					.withTemplateConfig(rbacConfig())
					.withServiceAccounts(List.of())
					.generate()
		);

		assertThat(ex.getMessage()).contains("At least one service account");
	}

	@Test
	void acceptsServiceAccountsViaWithServiceAccountsDirectly() {
		ServiceAccountRef serviceAccount = new ServiceAccountRef("myns", "mysa");

		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("direct")
			.withNamespace("myns")
			.withServiceAccounts(List.of(serviceAccount))
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File file = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac/rolebinding-direct-myns.yaml");
		assertThat(file).exists();
	}

	@Test
	void customSubfolderIsRespected() {
		String custom = "custom-dir";
		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("custom")
			.withNamespace("testing")
			.withSubfolder(custom)
			.withServiceAccountsFrom("testing", List.of("reader"))
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File out = new File(repo.getAbsoluteLocalRepoTmpDir(), custom);
		File[] yamlFiles = out.listFiles((FileFilter) file -> file.getName().endsWith(".yaml"));
		List<String> fileNames = Arrays.stream(yamlFiles).map(File::getName).toList();

		assertThat(yamlFiles).isNotEmpty();
		assertThat(fileNames).anyMatch(name -> name.contains("role") || name.contains("rolebinding"));
	}

	@Test
	void multipleServiceAccountsAreRenderedCorrectly() {
		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("multi")
			.withNamespace("testing")
			.withServiceAccountsFrom("testing", List.of("reader", "writer", "admin"))
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File[] files = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac").listFiles();
		List<String> fileNames = Arrays.stream(files).map(File::getName).toList();
		assertThat(fileNames).anyMatch(name -> name.contains("role"));
	}

	@Test
	void customRoleAndBindingFileNamesAreRendered() {
		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("myrole")
			.withNamespace("custom-ns")
			.withServiceAccountsFrom("custom-ns", List.of("sa1"))
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac");
		List<String> fileNames = Arrays.stream(outputDir.listFiles()).map(File::getName).toList();

		assertThat(fileNames).contains("role-myrole-custom-ns.yaml", "rolebinding-myrole-custom-ns.yaml");
	}

	@Test
	void subfolderCanBeNested() {
		String nested = "some/nested/path";
		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("nestedtest")
			.withNamespace("ns")
			.withServiceAccountsFrom("ns", List.of("sa1"))
			.withSubfolder(nested)
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), nested);
		List<String> fileNames = Arrays.stream(outputDir.listFiles()).map(File::getName).toList();

		assertThat(fileNames).contains("role-nestedtest-ns.yaml", "rolebinding-nestedtest-ns.yaml");
	}

	@Test
	void failsIfRepoIsNotSet() {
		IllegalStateException ex = assertThrows(
			IllegalStateException.class, () ->
				new RbacDefinition(Role.Variant.ARGOCD)
					.withName("failtest")
					.withNamespace("ns")
					.withServiceAccountsFrom("ns", List.of("sa1"))
					.withTemplateConfig(rbacConfig())
					.generate()
		);

		assertThat(ex.getMessage()).contains("SCMM repo must be set using withRepo() before calling generate()");
	}

	@Test
	@SuppressWarnings("unchecked")
	void renderedRolebindingYamlContainsCorrectServiceAccounts() throws IOException {
		List<String> serviceAccounts = List.of("reader", "writer");
		String namespace = "rbac-test";

		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("test")
			.withNamespace(namespace)
			.withServiceAccountsFrom(namespace, serviceAccounts)
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		String path = "rbac/rolebinding-test-" + namespace + ".yaml";
		File file = new File(repo.getAbsoluteLocalRepoTmpDir(), path);
		Map<String, Object> yaml = YAML_MAPPER.readValue(file, YAML_MAP_TYPE);
		Map<String, Object> metadata = (Map<String, Object>) yaml.get("metadata");

		assertThat(metadata.get("name")).isEqualTo("test");
		assertThat(metadata.get("namespace")).isEqualTo(namespace);

		List<Map<String, Object>> subjects = (List<Map<String, Object>>) yaml.get("subjects");
		List<String> names = subjects.stream().map(subject -> (String) subject.get("name")).toList();
		assertThat(names).containsExactlyInAnyOrderElementsOf(serviceAccounts);

		List<String> namespaces = subjects.stream().map(subject -> (String) subject.get("namespace")).toList();
		assertThat(namespaces).containsOnly(namespace);

		Map<String, Object> roleRef = (Map<String, Object>) yaml.get("roleRef");
		assertThat(roleRef.get("name")).isEqualTo("test");
		assertThat(roleRef.get("kind")).isEqualTo("Role");
	}

	@Test
	@SuppressWarnings("unchecked")
	void renderedRoleYamlContainsCorrectMetadata() throws IOException {
		String name = "myrole";
		String namespace = "custom-ns";

		new RbacDefinition(Role.Variant.ARGOCD)
			.withName(name)
			.withNamespace(namespace)
			.withServiceAccountsFrom(namespace, List.of("sa1"))
			.withRepo(repo)
			.withTemplateConfig(rbacConfig())
			.generate();

		String path = "rbac/role-" + name + "-" + namespace + ".yaml";
		File file = new File(repo.getAbsoluteLocalRepoTmpDir(), path);
		Map<String, Object> yaml = YAML_MAPPER.readValue(file, YAML_MAP_TYPE);
		Map<String, Object> metadata = (Map<String, Object>) yaml.get("metadata");

		assertThat(metadata.get("name")).isEqualTo(name);
		assertThat(metadata.get("namespace")).isEqualTo(namespace);
	}

	@Test
	@SuppressWarnings("unchecked")
	void rendersNodeAccessRulesInArgocdRoleOnlyWhenNotOnOpenShift() throws IOException {
		config.getApplication().setOpenshift(false);

		GitRepo tempRepo = new GitRepo(config, null, "rbac-test", new FileSystemUtils());

		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("nodecheck")
			.withNamespace("monitoring")
			.withServiceAccountsFrom("monitoring", List.of("sa1"))
			.withRepo(tempRepo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File roleFile = new File(tempRepo.getAbsoluteLocalRepoTmpDir(), "rbac/role-nodecheck-monitoring.yaml");
		Map<String, Object> yaml = YAML_MAPPER.readValue(roleFile, YAML_MAP_TYPE);
		List<Map<String, Object>> rules = (List<Map<String, Object>>) yaml.get("rules");

		assertThat(rules).anyMatch(rule -> {
			List<String> resources = (List<String>) rule.get("resources");
			List<String> verbs = (List<String>) rule.get("verbs");
			return resources.containsAll(List.of("nodes", "nodes/metrics"))
				&& verbs.containsAll(List.of("get", "list", "watch"));
		});
	}

	@Test
	@SuppressWarnings("unchecked")
	void doesNotRenderNodeAccessRulesInArgocdRoleWhenOnOpenShift() throws IOException {
		config.getApplication().setOpenshift(true);

		GitRepo tempRepo = new GitRepo(config, null, "rbac-test", new FileSystemUtils());

		new RbacDefinition(Role.Variant.ARGOCD)
			.withName("nodecheck")
			.withNamespace("monitoring")
			.withServiceAccountsFrom("monitoring", List.of("sa1"))
			.withRepo(tempRepo)
			.withTemplateConfig(rbacConfig())
			.generate();

		File roleFile = new File(tempRepo.getAbsoluteLocalRepoTmpDir(), "rbac/role-nodecheck-monitoring.yaml");
		Map<String, Object> yaml = YAML_MAPPER.readValue(roleFile, YAML_MAP_TYPE);
		List<Map<String, Object>> rules = (List<Map<String, Object>>) yaml.get("rules");

		assertThat(rules).noneMatch(rule -> {
			List<String> resources = (List<String>) rule.get("resources");
			return resources.contains("nodes") && resources.contains("nodes/metrics");
		});
	}

	@Test
	void failsIfConfigIsNotSet() {
		IllegalArgumentException ex = assertThrows(
			IllegalArgumentException.class, () ->
				new RbacDefinition(Role.Variant.ARGOCD)
					.withName("failtest")
					.withNamespace("ns")
					.withServiceAccountsFrom("ns", List.of("sa"))
					.withRepo(repo)
					.generate()
		);

		assertThat(ex.getMessage()).contains("Config must not be null");
	}

	private Map<String, Object> rbacConfig() {
		return Map.of(
			"application", Map.of("openshift", config.getApplication().getOpenshift()),
			"features", Map.of(
				"monitoring", Map.of("active", config.getFeatures().getMonitoring().getActive()),
				"secrets", Map.of("active", config.getFeatures().getSecrets().getActive())
			)
		);
	}
}
