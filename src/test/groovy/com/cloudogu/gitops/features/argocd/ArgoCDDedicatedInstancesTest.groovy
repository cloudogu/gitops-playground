package com.cloudogu.gitops.features.argocd

import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class ArgoCDDedicatedInstancesTest extends ArgoCDTest {


    @Test
    void 'GOP DedicatedInstances Central templating works correctly'() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.centralScmUrl = 'scmm.testhost/scm'
        config.multiTenant.username = 'testUserName'
        config.multiTenant.password = 'testPassword'

        def argocd = setupOperatorTest()

        argocd.install()

        String prefixPathCentral = '/multiTenant/central/'

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
        assertThat(tenantProject['spec']['sourceRepos'][0]).isEqualTo('http://scmm-scm-manager.scm-manager.svc.cluster.local/scm/repo/testPrefix-argocd/argocd')
        assertThat(tenantProject['spec']['sourceRepos'][1]).isEqualTo('http://scmm-scm-manager.scm-manager.svc.cluster.local/scm/repo/testPrefix-argocd/cluster-resources')

    }

    @Test
    void 'GOP DedicatedInstances Tenant templating works correctly'() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.centralScmUrl = 'scmm.testhost/scm'
        config.multiTenant.username = 'testUserName'
        config.multiTenant.password = 'testPassword'

        def argocd = setupOperatorTest()

        argocd.install()

        String prefixPathTenant = '/multiTenant/tenant/'

        //Tenant Projects
        assertThat(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathTenant}applications/argocd.yaml")).exists()
        def exampleAppsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathTenant}projects/example-apps.yaml")

    }

}