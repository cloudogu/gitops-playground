package com.cloudogu.gitops.tools.core.argocd

import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertThrows

import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests
import com.cloudogu.gitops.testhelper.git.TestGitProvider
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Path

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ArgoCDRepoSetupTest {

	Config config

	@BeforeEach
	void setUp() {
		config = Config.fromMap(application: [namePrefix: '',
		                                      netpols   : true,
		                                      namespaces: [dedicatedNamespaces: ["argocd", "monitoring", "secrets"],
		                                                   tenantNamespaces   : ["example-apps-staging", "example-apps-production"]]],
			scm: [scmProviderType: ScmProviderType.SCM_MANAGER,
			      scmManager     : [internal: true],
			      gitlab         : [url: '']],
			multiTenant: [scmManager            : [url: ''],
			              gitlab                : [url: ''],
			              useDedicatedInstance  : false,
			              centralArgocdNamespace: 'argocd'],
			features: [argocd     : [operator : false,
			                         active   : true,
			                         namespace: 'argocd'],
			           certManager: [active: false],
			           ingress    : [active: true],
			           monitoring : [active: true, helm: [chart: 'kube-prometheus-stack', version: '42.0.3']],
			           mail       : [active: false],
			           secrets    : [active: true],])
	}

	private ArgoCDRepoSetupTestContext createSetup(FileSystemUtils fs) {
		def providers = TestGitProvider.buildProviders(config)
		GitProvider tenantProvider = providers.tenant as GitProvider
		GitProvider centralProvider = providers.central as GitProvider

		def repoFactory = new TestGitRepoFactory(config, new FileSystemUtils())

		GitRepo clusterResourcesRepo = repoFactory.create(
			'argocd/cluster-resources',
			config.multiTenant.useDedicatedInstance ? centralProvider : tenantProvider
		)

		RepositoryWorkspace repositoryWorkspace

		if (config.multiTenant.useDedicatedInstance) {
			GitRepo tenantBootstrapRepo = repoFactory.create(
				'argocd/cluster-resources',
				tenantProvider
			)

			repositoryWorkspace = new RepositoryWorkspace(
				clusterResourcesRepo,
				tenantBootstrapRepo
			)
		} else {
			repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo)
		}

		def gitHandler = new GitHandlerForTests(
			config,
			tenantProvider,
			centralProvider
		)

		return new ArgoCDRepoSetupTestContext(
			setup: ArgoCDRepoSetup.create(
				config,
				fs,
				gitHandler,
				repositoryWorkspace
			),
			repositoryWorkspace: repositoryWorkspace
		)
	}

	@Test
	void 'create() single instance uses cluster-resources repository only'() {
		config.multiTenant.useDedicatedInstance = false

		def testContext = createSetup(new FileSystemUtils())

		assertThat(testContext.repositoryWorkspace.clusterResourcesRepository).isNotNull()
		assertThat(testContext.repositoryWorkspace.clusterResourcesRepository.repoTarget).isEqualTo('argocd/cluster-resources')
		assertThat(testContext.repositoryWorkspace.hasTenantBootstrapRepository()).isFalse()

		assertThat(testContext.setup.clusterRepoLayout()).isNotNull()
	}

	@Test
	void 'create() dedicated instance uses cluster-resources and tenant-bootstrap repositories from workspace'() {
		config.multiTenant.useDedicatedInstance = true

		def testContext = createSetup(new FileSystemUtils())

		assertThat(testContext.repositoryWorkspace.clusterResourcesRepository).isNotNull()
		assertThat(testContext.repositoryWorkspace.tenantBootstrapRepository).isNotNull()
		assertThat(testContext.repositoryWorkspace.hasTenantBootstrapRepository()).isTrue()

		assertThat(testContext.setup.clusterRepoLayout()).isNotNull()
		assertThat(testContext.setup.tenantRepoLayout()).isNotNull()
	}

	@Test
	void 'tenantRepoLayout throws in single instance mode'() {
		config.multiTenant.useDedicatedInstance = false

		def setup = createSetup(new FileSystemUtils()).setup

		assertThrows(IllegalStateException) {
			setup.tenantRepoLayout()
		}
	}

	@Test
	void 'tenantRepoLayout is available in dedicated instance mode'() {
		config.multiTenant.useDedicatedInstance = true

		def setup = createSetup(new FileSystemUtils()).setup

		assertThat(setup.tenantRepoLayout()).isNotNull()
	}

	@Test
	void 'prepareRepositories deletes helmDir when operator is enabled'() {
		config.features.argocd.operator = true
		config.multiTenant.useDedicatedInstance = false
		config.application.netpols = true

		def setup = createSetup(new FileSystemUtils()).setup

		setup.prepareRepositories()

		def clusterRepoLayout = setup.clusterRepoLayout()

		assertThat(Path.of(clusterRepoLayout.helmDir())).doesNotExist()
	}

	@Test
	void 'prepareRepositories deletes operatorDir when operator is disabled'() {
		config.features.argocd.operator = false
		config.multiTenant.useDedicatedInstance = false
		config.application.netpols = true

		def setup = createSetup(new FileSystemUtils()).setup

		setup.prepareRepositories()

		def clusterRepoLayout = setup.clusterRepoLayout()

		assertThat(Path.of(clusterRepoLayout.operatorDir())).doesNotExist()
		assertThat(Path.of(clusterRepoLayout.helmDir())).exists()
	}

	@Test
	void 'prepareRepositories in dedicated mode replaces single-instance resources with central resources'() {
		config.features.argocd.operator = false
		config.multiTenant.useDedicatedInstance = true
		config.application.netpols = true

		def setup = createSetup(new FileSystemUtils()).setup

		setup.prepareRepositories()

		def clusterRepoLayout = setup.clusterRepoLayout()

		assertThat(Path.of(clusterRepoLayout.applicationsDir())).exists()
		assertThat(Path.of(clusterRepoLayout.projectsDir())).exists()
		assertThat(Path.of(clusterRepoLayout.multiTenantDir())).doesNotExist()
	}

	@Test
	void 'prepareRepositories in single instance deletes multiTenant folder'() {
		config.features.argocd.operator = false
		config.multiTenant.useDedicatedInstance = false
		config.application.netpols = true

		def setup = createSetup(new FileSystemUtils()).setup

		setup.prepareRepositories()

		def clusterRepoLayout = setup.clusterRepoLayout()

		assertThat(Path.of(clusterRepoLayout.multiTenantDir())).doesNotExist()
	}

	@Test
	void 'prepareRepositories deletes netpol file when netpols disabled'() {
		config.application.netpols = false

		def setup = createSetup(new FileSystemUtils()).setup

		setup.prepareRepositories()

		def clusterRepoLayout = setup.clusterRepoLayout()

		assertThat(Path.of(clusterRepoLayout.netpolFile())).doesNotExist()
	}

	@Test
	void 'prepareRepositories keeps netpol file when netpols enabled'() {
		config.application.netpols = true

		def setup = createSetup(new FileSystemUtils()).setup

		setup.prepareRepositories()

		def clusterRepoLayout = setup.clusterRepoLayout()

		assertThat(Path.of(clusterRepoLayout.netpolFile())).exists()
	}

	@Test
	void 'prepareRepositories copies ingress resources when ingress feature is active'() {
		config.features.ingress.active = true

		def testContext = createSetup(new FileSystemUtils())

		testContext.setup.prepareRepositories()

		assertThat(Path.of(
			testContext.repositoryWorkspace.clusterResourcesRootDir(),
			ArgoCDRepoLayout.ingressSubdirRel()
		)).exists()
	}

	@Test
	void 'prepareRepositories does not copy monitoring resources when monitoring feature is inactive'() {
		config.features.monitoring.active = false

		def testContext = createSetup(new FileSystemUtils())

		testContext.setup.prepareRepositories()

		assertThat(Path.of(
			testContext.repositoryWorkspace.clusterResourcesRootDir(),
			ArgoCDRepoLayout.monitoringSubdirRel()
		)).doesNotExist()
	}

	@Test
	void 'prepareRepositories copies secrets and vault resources when secrets feature is active'() {
		config.features.secrets.active = true

		def testContext = createSetup(new FileSystemUtils())

		testContext.setup.prepareRepositories()

		assertThat(Path.of(
			testContext.repositoryWorkspace.clusterResourcesRootDir(),
			ArgoCDRepoLayout.secretsSubdirRel()
		)).exists()

		assertThat(Path.of(
			testContext.repositoryWorkspace.clusterResourcesRootDir(),
			ArgoCDRepoLayout.vaultSubdirRel()
		)).exists()
	}

	@Test
	void 'prepareRepositories prepares tenant bootstrap repository in dedicated mode'() {
		config.multiTenant.useDedicatedInstance = true

		def testContext = createSetup(new FileSystemUtils())

		testContext.setup.prepareRepositories()

		assertThat(Path.of(testContext.repositoryWorkspace.tenantBootstrapRootDir())).exists()
		assertThat(Path.of(testContext.repositoryWorkspace.tenantBootstrapRootDir()).toFile().listFiles()).isNotEmpty()
	}

	@Test
	void 'prepareRepositories does not prepare tenant bootstrap repository in single instance mode'() {
		config.multiTenant.useDedicatedInstance = false

		def testContext = createSetup(new FileSystemUtils())

		testContext.setup.prepareRepositories()

		assertThat(testContext.repositoryWorkspace.hasTenantBootstrapRepository()).isFalse()
	}

	static class ArgoCDRepoSetupTestContext {
		ArgoCDRepoSetup setup
		RepositoryWorkspace repositoryWorkspace
	}
}