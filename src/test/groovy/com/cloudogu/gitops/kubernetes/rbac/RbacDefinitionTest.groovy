package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertThrows

class RbacDefinitionTest {

    private final Config config = Config.fromMap([
            scm        : [
                    scmManager: [
                            username: 'user',
                            password: 'pass',
                            protocol: 'http',
                            host    : 'localhost',
                            rootPath: 'scm'
                    ],
            ],
            application: [
                    namePrefix: '',
                    insecure  : false,
                    gitName   : 'Test User',
                    gitEmail  : 'test@example.com'
            ]
    ])

    private final GitRepo repo = new GitRepo(config, null, "my-repo", new FileSystemUtils())

    @Test
    void 'generates at least one RBAC YAML file'() {
        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("access")
                .withNamespace("testing")
                .withServiceAccountsFrom("testing", ["reader"])
                .withRepo(repo)
                .withConfig(config)
                .generate()

        File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac")
        File[] yamlFiles = outputDir.listFiles({ file -> file.name.endsWith(".yaml") } as FileFilter)
        List<String> fileNames = yamlFiles.collect { it.name }

        assertThat(yamlFiles).isNotEmpty()
        assertThat(fileNames).anyMatch { it.contains("role") || it.contains("rolebinding") }
    }

    @Test
    void 'fails if name is missing'() {
        def ex = assertThrows(IllegalArgumentException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withNamespace("testing")
                    .withServiceAccountsFrom("testing", ["reader"])
                    .withRepo(repo)
                    .withConfig(config)
                    .generate()
        }

        assertThat(ex.message).contains("name must not be blank")
    }


    @Test
    void 'fails if namespace is missing'() {
        def ex = assertThrows(IllegalArgumentException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("access")
                    .withServiceAccountsFrom("testing", ["reader"])
                    .withRepo(repo)
                    .withConfig(config)
                    .generate()
        }

        assertThat(ex.message).contains("namespace must not be blank")
    }

    @Test
    void 'fails if service accounts are empty'() {
        def ex = assertThrows(IllegalArgumentException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("access")
                    .withNamespace("testing")
                    .withRepo(repo)
                    .withConfig(config)
                    .withServiceAccounts([]) // leer übergeben
                    .generate()
        }
        assertThat(ex.message).contains("At least one service account")
    }

    @Test
    void 'accepts service accounts via withServiceAccounts directly'() {
        def sa = new ServiceAccountRef("myns", "mysa")

        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("direct")
                .withNamespace("myns")
                .withServiceAccounts([sa])
                .withRepo(repo)
                .withConfig(config)
                .generate()

        File f = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac/rolebinding-direct-myns.yaml")
        assertThat(f).exists()
    }

    @Test
    void 'custom subfolder is respected'() {
        String custom = "custom-dir"
        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("custom")
                .withNamespace("testing")
                .withSubfolder(custom)
                .withServiceAccountsFrom("testing", ["reader"])
                .withRepo(repo)
                .withConfig(config)
                .generate()

        File out = new File(repo.getAbsoluteLocalRepoTmpDir(), custom)
        File[] yamlFiles = out.listFiles({ file -> file.name.endsWith(".yaml") } as FileFilter)
        List<String> fileNames = yamlFiles.collect { it.name }

        assertThat(yamlFiles).isNotEmpty()
        assertThat(fileNames).anyMatch { it.contains("role") || it.contains("rolebinding") }
    }

    @Test
    void 'multiple service accounts are rendered correctly'() {
        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("multi")
                .withNamespace("testing")
                .withServiceAccountsFrom("testing", ["reader", "writer", "admin"])
                .withRepo(repo)
                .withConfig(config)
                .generate()

        File[] files = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac").listFiles()
        List<String> fileNames = files.collect { it.name }
        assertThat(fileNames).anyMatch { it.contains("role") }
    }

    @Test
    void 'custom role and binding file names are rendered'() {
        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("myrole")
                .withNamespace("custom-ns")
                .withServiceAccountsFrom("custom-ns", ["sa1"])
                .withRepo(repo)
                .withConfig(config)
                .generate()

        File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac")
        List<String> fileNames = outputDir.listFiles().collect { it.name }

        assertThat(fileNames).contains("role-myrole-custom-ns.yaml", "rolebinding-myrole-custom-ns.yaml")
    }

    @Test
    void 'subfolder can be nested'() {
        String nested = "some/nested/path"
        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("nestedtest")
                .withNamespace("ns")
                .withServiceAccountsFrom("ns", ["sa1"])
                .withSubfolder(nested)
                .withRepo(repo)
                .withConfig(config)
                .generate()

        File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), nested)
        List<String> fileNames = outputDir.listFiles().collect { it.name }

        assertThat(fileNames).contains("role-nestedtest-ns.yaml", "rolebinding-nestedtest-ns.yaml")
    }

    @Test
    void 'fails if repo is not set'() {
        IllegalStateException ex = assertThrows(IllegalStateException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("failtest")
                    .withNamespace("ns")
                    .withServiceAccountsFrom("ns", ["sa1"])
                    .withConfig(config)
                    .generate()
        }

        assertThat(ex.message).contains("SCMM repo must be set using withRepo() before calling generate()")
    }

    @Test
    void 'rendered rolebinding yaml contains correct service accounts'() {
        List<String> saList = ["reader", "writer"]
        String ns = "rbac-test"

        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("test")
                .withNamespace(ns)
                .withServiceAccountsFrom(ns, saList)
                .withRepo(repo)
                .withConfig(config)
                .generate()

        String path = "rbac/rolebinding-test-${ns}.yaml".toString()
        File file = new File(repo.getAbsoluteLocalRepoTmpDir(), path)
        Map yaml = new YamlSlurper().parse(file) as Map

        assertThat(yaml["metadata"]["name"]).isEqualTo("test")
        assertThat(yaml["metadata"]["namespace"]).isEqualTo(ns)

        List<String> names = yaml["subjects"].collect { it['name'] as String }
        assertThat(names).containsExactlyInAnyOrderElementsOf(saList)

        List<String> namespaces = yaml["subjects"].collect { it['namespace'] as String }
        assertThat(namespaces).containsOnly(ns)

        assertThat(yaml["roleRef"]["name"]).isEqualTo("test")
        assertThat(yaml["roleRef"]["kind"]).isEqualTo("Role")
    }

    @Test
    void 'rendered role yaml contains correct metadata'() {
        String name = "myrole"
        String ns = "custom-ns"

        new RbacDefinition(Role.Variant.ARGOCD)
                .withName(name)
                .withNamespace(ns)
                .withServiceAccountsFrom(ns, ["sa1"])
                .withRepo(repo)
                .withConfig(config)
                .generate()

        String path = "rbac/role-${name}-${ns}.yaml".toString()
        File file = new File(repo.getAbsoluteLocalRepoTmpDir(), path)
        Map yaml = new YamlSlurper().parse(file) as Map

        assertThat(yaml["metadata"]["name"]).isEqualTo(name)
        assertThat(yaml["metadata"]["namespace"]).isEqualTo(ns)
    }

    @Test
    void 'renders node access rules in argocd-role only when not on OpenShift'() {
        config.application.openshift = false

        GitRepo tempRepo = new GitRepo(config, null, "rbac-test", new FileSystemUtils())

        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("nodecheck")
                .withNamespace("monitoring")
                .withServiceAccountsFrom("monitoring", ["sa1"])
                .withRepo(tempRepo)
                .withConfig(config)
                .generate()

        File roleFile = new File(tempRepo.getAbsoluteLocalRepoTmpDir(), "rbac/role-nodecheck-monitoring.yaml")
        Map yaml = new YamlSlurper().parse(roleFile) as Map
        List<Map> rules = yaml["rules"] as List<Map>

        assertThat(rules).anyMatch { rule ->
            List<String> resources = rule["resources"] as List<String>
            List<String> verbs = rule["verbs"] as List<String>
            resources.containsAll(["nodes", "nodes/metrics"]) &&
                    verbs.containsAll(["get", "list", "watch"])
        }
    }

    @Test
    void 'does not render node access rules in argocd-role  when on OpenShift'() {
        config.application.openshift = true

        GitRepo tempRepo = new GitRepo(config, null, "rbac-test", new FileSystemUtils())

        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("nodecheck")
                .withNamespace("monitoring")
                .withServiceAccountsFrom("monitoring", ["sa1"])
                .withRepo(tempRepo)
                .withConfig(config)
                .generate()

        File roleFile = new File(tempRepo.getAbsoluteLocalRepoTmpDir(), "rbac/role-nodecheck-monitoring.yaml")
        Map yaml = new YamlSlurper().parse(roleFile) as Map
        List<Map> rules = yaml["rules"] as List<Map>

        assertThat(rules).noneMatch { rule ->
            List<String> resources = rule["resources"] as List<String>
            resources.contains("nodes") && resources.contains("nodes/metrics")
        }
    }

    @Test
    void 'fails if config is not set'() {
        def ex = assertThrows(IllegalArgumentException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("failtest")
                    .withNamespace("ns")
                    .withServiceAccountsFrom("ns", ["sa"])
                    .withRepo(repo)
                    .generate()
        }

        assertThat(ex.message).contains("Config must not be null")
        // oder je nach deiner tatsächlichen Exception-Message
    }

}