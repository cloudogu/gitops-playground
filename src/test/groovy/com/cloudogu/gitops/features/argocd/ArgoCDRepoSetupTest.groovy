package com.cloudogu.gitops.features.argocd

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.TestGitProvider
import com.cloudogu.gitops.utils.git.TestGitRepoFactory

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertThrows

class ArgoCDRepoSetupTest {

    Config config
    GitProvider tenantProvider
    GitProvider centralProvider

    @BeforeEach
    void setUp() {
        config = Config.fromMap(
                application: [
                        namePrefix: '',
                        netpols   : true,
                        namespaces: [
                                dedicatedNamespaces: ["argocd", "monitoring", "ingress-nginx", "secrets"],
                                tenantNamespaces   : ["example-apps-staging", "example-apps-production"]
                        ]
                ],
                scm: [
                        scmManager: [internal: true],
                        gitlab    : [url: '']
                ],
                multiTenant: [
                        scmManager            : [url: ''],
                        gitlab                : [url: ''],
                        useDedicatedInstance  : false,
                        centralArgocdNamespace: 'argocd'
                ],
                features: [
                        argocd      : [
                                operator : false,
                                active   : true,
                                namespace: 'argocd'
                        ],
                        certManager : [active: false],
                        ingress: [active: true],
                        monitoring  : [active: true, helm: [chart: 'kube-prometheus-stack', version: '42.0.3']],
                        mail        : [active: false],
                        secrets     : [active: true],
                ]
        )

        def providers = TestGitProvider.buildProviders(config)
        tenantProvider = providers.tenant as GitProvider
        centralProvider = providers.central as GitProvider
    }

    private ArgoCDRepoSetup createSetup(FileSystemUtils fs) {
        def repoFactory = new TestGitRepoFactory(config, new FileSystemUtils())
        repoFactory.defaultProvider = tenantProvider

        def gitHandler = new GitHandlerForTests(config, tenantProvider, centralProvider)
        return ArgoCDRepoSetup.create(config, fs, repoFactory, gitHandler)
    }

    @Test
    void 'create() single instance creates only cluster-resources and no tenantBootstrap'() {
        config.multiTenant.useDedicatedInstance = false

        def setup = createSetup(new FileSystemUtils())

        assertThat(setup.tenantBootstrap).isNull()
        assertThat(setup.clusterResources).isNotNull()
        assertThat(setup.allRepos).hasSize(1)
        assertThat(setup.clusterResources.repo.repoTarget).isEqualTo('argocd/cluster-resources')
    }

    @Test
    void 'create() dedicated instance creates tenantBootstrap and clusterResources'() {
        config.multiTenant.useDedicatedInstance = true

        def setup = createSetup(new FileSystemUtils())

        assertThat(setup.tenantBootstrap).isNotNull()
        assertThat(setup.clusterResources).isNotNull()
        assertThat(setup.allRepos).hasSize(2)
    }

    @Test
    void 'tenantRepoLayout throws in single instance mode'() {
        config.multiTenant.useDedicatedInstance = false

        def setup = createSetup(new FileSystemUtils())

        assertThrows(IllegalStateException) {
            setup.tenantRepoLayout()
        }
    }

    @Test
    void 'prepareClusterResourcesRepo deletes helmDir when operator is enabled'() {
        config.features.argocd.operator = true
        config.multiTenant.useDedicatedInstance = false
        config.application.netpols = true
        def setup = createSetup(new FileSystemUtils())

        setup.initLocalRepos()
        setup.prepareClusterResourcesRepo()

        def clusterRepoLayout = setup.clusterRepoLayout()
        assertThat(Path.of(clusterRepoLayout.helmDir())).doesNotExist()
    }

    @Test
    void 'prepareClusterResourcesRepo deletes operatorDir when operator is disabled'() {
        config.features.argocd.operator = false
        config.multiTenant.useDedicatedInstance = false
        config.application.netpols = true

        def setup = createSetup(new FileSystemUtils())

        setup.initLocalRepos()
        setup.prepareClusterResourcesRepo()

        def clusterRepoLayout = setup.clusterRepoLayout()
        assertThat(Path.of(clusterRepoLayout.operatorDir())).doesNotExist()
        assertThat(Path.of(clusterRepoLayout.helmDir())).exists()

    }

    @Test
    void 'prepareClusterResourcesRepo in dedicated mode deletes multiTenant folder'() {
        config.features.argocd.operator = false
        config.multiTenant.useDedicatedInstance = true
        config.application.netpols = true

        def setup = createSetup(new FileSystemUtils())

        setup.initLocalRepos()
        setup.prepareClusterResourcesRepo()

        def clusterRepoLayout = setup.clusterRepoLayout()

        assertThat(Path.of(clusterRepoLayout.applicationsDir())).exists()
        assertThat(Path.of(clusterRepoLayout.projectsDir())).exists()
        assertThat(Path.of(clusterRepoLayout.multiTenantDir())).doesNotExist()
    }

    @Test
    void 'prepareClusterResourcesRepo in single instance deletes multiTenant folder'() {
        config.features.argocd.operator = false
        config.multiTenant.useDedicatedInstance = false
        config.application.netpols = true

        def setup = createSetup(new FileSystemUtils())

        setup.initLocalRepos()
        setup.prepareClusterResourcesRepo()

        def clusterRepoLayout = setup.clusterRepoLayout()
        assertThat(Path.of(clusterRepoLayout.multiTenantDir())).doesNotExist()
    }

    @Test
    void 'prepareClusterResourcesRepo deletes netpol file when netpols disabled'() {
        config.application.netpols = false

        def setup = createSetup(new FileSystemUtils())

        setup.initLocalRepos()
        setup.prepareClusterResourcesRepo()

        def clusterRepoLayout = setup.clusterRepoLayout()
        assertThat(Path.of(clusterRepoLayout.netpolFile())).doesNotExist()
    }

    @Test
    void 'create() sets subDirsToCopy based on enabled features'() {
        config.features.ingress.active = true
        config.features.monitoring.active = false
        config.features.secrets.active = false
        config.jenkins.active = false
        config.features.mail.active = false
        config.features.certManager.active = false

        def setup = createSetup(new FileSystemUtils())
        def dirs = setup.clusterResources.subDirsToCopy as Set<String>

        assertThat(dirs).contains(RepoLayout.argocdSubdirRel())
        assertThat(dirs).contains(RepoLayout.ingressSubdirRel())

        assertThat(dirs).doesNotContain(RepoLayout.monitoringSubdirRel())
        assertThat(dirs).doesNotContain(RepoLayout.secretsSubdirRel())
        assertThat(dirs).doesNotContain(RepoLayout.vaultSubdirRel())
        assertThat(dirs).doesNotContain(RepoLayout.jenkinsSubdirRel())
        assertThat(dirs).doesNotContain(RepoLayout.mailhogSubdirRel())
        assertThat(dirs).doesNotContain(RepoLayout.certManagerSubdirRel())
    }

    @Test
    void 'create() includes secrets + vault subdirs when secrets feature active'() {
        config.features.secrets.active = true

        def setup = createSetup(new FileSystemUtils())
        def dirs = setup.clusterResources.subDirsToCopy as Set<String>

        assertThat(dirs).contains(RepoLayout.secretsSubdirRel())
        assertThat(dirs).contains(RepoLayout.vaultSubdirRel())
    }
}