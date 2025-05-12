package com.cloudogu.gitops.features.argocd

import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class ArgoCDDedicatedInstancesTest extends ArgoCDTest {


    ArgoCD argocd

    void setup() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.centralScmUrl = 'scmm.testhost/scm'
        config.multiTenant.username = 'testUserName'
        config.multiTenant.password = 'testPassword'
        config.multiTenant.useDedicatedInstance = true

        this.argocd = setupOperatorTest()
        argocd.install()
    }


    @Test
    void 'GOP DedicatedInstances Central templating works correctly'() {
        String prefixPathCentral = '/multiTenant/central/'
        setup()
        //Central Applications
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/argocd.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/bootstrap.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/cluster-resources.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/projects.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/example-apps.yaml")).doesNotExist()

        def argocdYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/argocd.yaml")
        def bootstrapYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/bootstrap.yaml")
        def clusterResourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/cluster-resources.yaml")
        def projectsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/projects.yaml")

        assertThat(argocdYaml['metadata']['name']).isEqualTo('testPrefix-argocd')
        assertThat(argocdYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(argocdYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('operator/')

        assertThat(bootstrapYaml['metadata']['name']).isEqualTo('testPrefix-bootstrap')
        assertThat(bootstrapYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(bootstrapYaml['spec']['project']).isEqualTo('testPrefix')

        assertThat(clusterResourcesYaml['metadata']['name']).isEqualTo('testPrefix-cluster-resources')
        assertThat(clusterResourcesYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(clusterResourcesYaml['spec']['project']).isEqualTo('testPrefix')

        assertThat(projectsYaml['metadata']['name']).isEqualTo('testPrefix-projects')
        assertThat(projectsYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(projectsYaml['spec']['project']).isEqualTo('testPrefix')

        //Central Project
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}projects/tenant.yaml")).exists()

        def tenantProject = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}projects/tenant.yaml")

        assertThat(tenantProject['metadata']['name']).isEqualTo('testPrefix')
        assertThat(tenantProject['metadata']['namespace']).isEqualTo('argocd')
        def sourceRepos = (List<String>) tenantProject['spec']['sourceRepos']
        assertThat(sourceRepos[0]).isEqualTo('scmm.testhost/scm/repo/testPrefix-argocd/argocd')
        assertThat(sourceRepos[1]).isEqualTo('scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources')
    }

    @Test
    void 'Cluster Resource Misc templating'() {
        setup()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/argocd/misc.yaml")).exists()
        def miscYaml = new YamlSlurper().parse(Path.of clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), "/argocd/misc.yaml")
        assertThat(miscYaml['metadata']['name']).isEqualTo('testPrefix-misc')
        assertThat(miscYaml['metadata']['namespace']).isEqualTo('argocd')
    }

    @Test
    void 'generate example-apps bootstrapping application via ArgoApplication'() {
        setup()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/bootstrap.yaml")).exists()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/argocd-application-example-apps-testPrefix-argocd.yaml")).exists()
        config.content.examples = false
        setup()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/bootstrap.yaml")).exists()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/argocd-application-example-apps-testPrefix-argocd.yaml")).doesNotExist()
    }

    @Test
    void 'Append namespaces to Argocd argocd-default-cluster-config secrets'() {
        config.application.namespaces.dedicatedNamespaces = new LinkedHashSet(['dedi-test1', 'dedi-test2', 'dedi-test3'])
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['tenant-test1', 'tenant-test2', 'tenant-test3'])

        setup()
        k8sCommands.assertExecuted('kubectl get secret argocd-default-cluster-config -nargocd -ojsonpath={.data.namespaces}')
        k8sCommands.assertExecuted('kubectl patch secret argocd-default-cluster-config -n argocd --patch-file=/tmp/gitops-playground-patch-')
    }

    @Test
    void 'RBACs generated correctly'() {
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['testprefix-tenant-test1', 'testprefix-tenant-test2', 'testprefix-tenant-test3'])
        setup()

        File rbacFolder = new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "/operator/rbac")
        File rbacTenantFolder = new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "/operator/rbac/tenant")
        assertThat(rbacFolder).exists()
        assertThat(rbacTenantFolder).exists()

        assertThat(rbacFolder.listFiles().count { it.isFile() }).isEqualTo(14)
        assertThat(rbacTenantFolder.listFiles().count { it.isFile() }).isEqualTo(6)

        rbacFolder.eachFile { file ->
            if (file.name.startsWith("role-") && file.name.contains('dedi')) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['metadata']['namespace']).isIn(config.application.namespaces.getActiveNamespaces())
            }
            if (file.name.startsWith("rolebinding-") && file.name.contains('dedi')) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['subjects']['namespace']).isEqualTo(["argocd", "argocd", "argocd"])
            }
        }

        rbacTenantFolder.eachFile { file ->
            if (file.name.startsWith("role-")) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['metadata']['namespace']).isIn(config.application.namespaces.tenantNamespaces)
            }

            if (file.name.startsWith("rolebinding-")) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['subjects']['namespace']).isEqualTo(["testPrefix-argocd", "testPrefix-argocd", "testPrefix-argocd"])
            }
        }

    }

    @Test
    void 'ArgoCD uses central multi tenant scm for repos'() {
        config.multiTenant.centralScmUrl = "scmm-central.localhost/scm"
        config.application.namePrefix = "foo-"
        config.multiTenant.useDedicatedInstance = true
        createArgoCD().install()
        def argocdYaml = new YamlSlurper().parse(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/argocd.yaml'))
        assertThat(argocdYaml['spec']['source']['repoURL']).isEqualTo('scmm-central.localhost/scm/repo/foo-argocd/argocd')
    }
}