package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertThrows

class RbacDefinitionTest {

    private final Config config = Config.fromMap([
            scmm: [
                    username: 'user',
                    password: 'pass',
                    protocol: 'http',
                    host: 'localhost',
                    provider: 'scm-manager',
                    rootPath: 'scm'
            ],
            application: [
                    namePrefix: '',
                    insecure: false,
                    gitName: 'Test User',
                    gitEmail: 'test@example.com'
            ]
    ])

    private final ScmmRepo repo = new ScmmRepo(config, "my-repo", new FileSystemUtils())

    @Test
    void 'generates at least one RBAC YAML file'() {
        new RbacDefinition(Role.Variant.ARGOCD)
                .withName("access")
                .withNamespace("testing")
                .withServiceAccountsFrom("testing", ["reader"])
                .withRepo(repo)
                .generate()

        File outputDir = new File(repo.getAbsoluteLocalRepoTmpDir(), "rbac")
        File[] yamlFiles = outputDir.listFiles({ file -> file.name.endsWith(".yaml") } as FileFilter)
        List<String> fileNames = yamlFiles.collect { it.name }

        assertThat(yamlFiles).isNotEmpty()
        assertThat(fileNames).anyMatch { it.contains("role") || it.contains("rolebinding") }
    }

    @Test
    void 'fails if name is missing'() {
        IllegalStateException ex = assertThrows(IllegalStateException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withNamespace("testing")
                    .withServiceAccountsFrom("testing", ["reader"])
                    .withRepo(repo)
                    .generate()
        }
        assertThat(ex.message).contains("RBAC definition requires a non-empty name")
    }

    @Test
    void 'fails if namespace is missing'() {
        IllegalStateException ex = assertThrows(IllegalStateException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("access")
                    .withServiceAccountsFrom("testing", ["reader"])
                    .withRepo(repo)
                    .generate()
        }
        assertThat(ex.message).contains("RBAC definition requires a non-empty namespace")
    }

    @Test
    void 'fails if service accounts are empty'() {
        IllegalStateException ex = assertThrows(IllegalStateException) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("access")
                    .withNamespace("testing")
                    .withRepo(repo)
                    .generate()
        }
        assertThat(ex.message).contains("at least one service account")
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
                .generate()

        String path = "rbac/role-${name}-${ns}.yaml".toString()
        File file = new File(repo.getAbsoluteLocalRepoTmpDir(), path)
        Map yaml = new YamlSlurper().parse(file) as Map

        assertThat(yaml["metadata"]["name"]).isEqualTo(name)
        assertThat(yaml["metadata"]["namespace"]).isEqualTo(ns)
    }
}
