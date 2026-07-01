package com.cloudogu.gitops.infrastructure.deployment

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.config.scm.ScmTenantSchema.ScmManagerTenantConfig
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.testhelper.git.ScmManagerMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.utils.FileSystemUtils

import groovy.yaml.YamlSlurper

import org.junit.jupiter.api.Test

class ArgoCdApplicationStrategyTest {
	private File localTempDir
	private RepositoryProvisioning repositoryProvisioning

	@Test
	void 'deploys feature using argo CD'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'foo-namespace',
			'releaseName',
			valuesYaml.toPath())

		def argoCdApplicationYaml = new File("$localTempDir/apps/argocd/applications/releaseName.yaml")

		assertThat(argoCdApplicationYaml.text).isEqualTo("""---
apiVersion: "argoproj.io/v1alpha1"
kind: "Application"
metadata:
  name: "foo-repoName"
  namespace: "foo-argocd"
spec:
  destination:
    server: "https://kubernetes.default.svc"
    namespace: "foo-namespace"
  project: "cluster-resources"
  sources:
  - repoURL: "repoURL"
    chart: "chartName"
    targetRevision: "version"
    helm:
      releaseName: "releaseName"
      valueFiles:
      - "\$values/apps/repoName/repoName-gop-helm.yaml"
      - "\$values/apps/repoName/repoName-user-values.yaml"
      ignoreMissingValueFiles: true
  - repoURL: "http://scmm.scm-manager.svc.cluster.local/scm/repo/argocd/cluster-resources.git"
    targetRevision: "main"
    ref: "values"
    path: "apps/repoName"
    directory:
      recurse: true
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
    - "ServerSideApply=true"
    - "CreateNamespace=true"
""")
	}

	@Test
	void 'deploys feature using argo CD from git repo'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'namespace',
			'releaseName',
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.GIT)

		def argoCdApplicationYaml = new File("$localTempDir/apps/argocd/applications/releaseName.yaml")
		def result = new YamlSlurper().parse(argoCdApplicationYaml)
		def sources = result['spec']['sources'] as List

		assertThat(sources[0] as Map).containsKey('path')
		assertThat(sources[0]['path']).isEqualTo('chartName')
	}

	@Test
	void 'deploys feature with argocdOperator true, setting CreateNamespace to false'() {
		def strategy = createStrategy(true)
		File valuesYaml = File.createTempFile('values', 'yaml')
		valuesYaml.text = '''
    param1: value1
    param2: value2
    '''

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'namespace',
			'releaseName',
			valuesYaml.toPath())

		def argoCdApplicationYaml = new File("$localTempDir/apps/argocd/applications/releaseName.yaml")

		assertThat(argoCdApplicationYaml.text).contains('CreateNamespace=false')
	}

	@Test
	void 'deploys feature with argocdOperator false, setting CreateNamespace to true'() {
		def strategy = createStrategy(false)
		File valuesYaml = File.createTempFile('values', 'yaml')
		valuesYaml.text = '''
    param1: value1
    param2: value2
    '''

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'namespace',
			'releaseName',
			valuesYaml.toPath())

		def argoCdApplicationYaml = new File("$localTempDir/apps/argocd/applications/releaseName.yaml")

		assertThat(argoCdApplicationYaml.text).contains('CreateNamespace=true')
	}

	@Test
	void 'deploys scm-manager as bootstrap application without values source'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')
		valuesYaml.text = '''
fullnameOverride: tenant1-scmm
service:
  type: NodePort
'''

		strategy.deployFeature('repoURL',
			'scm-manager',
			'scm-manager',
			'3.11.6',
			'tenant1-scm-manager',
			'tenant1-scmm',
			valuesYaml.toPath())

		def argoCdApplicationYaml = new File("$localTempDir/apps/argocd/applications/tenant1-scmm.yaml")
		def result = new YamlSlurper().parse(argoCdApplicationYaml)

		def sources = result['spec']['sources'] as List

		assertThat(sources).hasSize(1)
		assertThat(sources[0]['repoURL']).isEqualTo('repoURL')
		assertThat(sources[0]['chart']).isEqualTo('scm-manager')
		assertThat(sources[0]['helm']['releaseName']).isEqualTo('tenant1-scmm')
		assertThat(sources[0]['helm']['values'].toString()).contains('fullnameOverride: tenant1-scmm')
	}

	@Test
	void 'deploys scm-manager as bootstrap application without writing external value files'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')
		valuesYaml.text = '''
fullnameOverride: tenant1-scmm
'''

		strategy.deployFeature('repoURL',
			'scm-manager',
			'scm-manager',
			'3.11.6',
			'tenant1-scm-manager',
			'tenant1-scmm',
			valuesYaml.toPath())

		assertThat(new File("$localTempDir/apps/scm-manager/scm-manager-gop-helm.yaml")).doesNotExist()
		assertThat(new File("$localTempDir/apps/scm-manager/scm-manager-user-values.yaml")).doesNotExist()
	}

	@Test
	void 'deploys normal feature with gop and user values files'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')
		valuesYaml.text = '''
param1: value1
'''

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'namespace',
			'releaseName',
			valuesYaml.toPath())

		assertThat(new File("$localTempDir/apps/repoName/repoName-gop-helm.yaml").text)
			.contains('param1: value1')

		assertThat(new File("$localTempDir/apps/repoName/repoName-user-values.yaml"))
			.exists()
	}

	@Test
	void 'publishes cluster-resources changes through repository provisioning'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'namespace',
			'releaseName',
			valuesYaml.toPath())

		verify(repositoryProvisioning).publishClusterResourcesRepositoryChanges(eq('repoName'),
			eq('Add foo-repoName/chartName to ArgoCD'))
	}

	@Test
	void 'uses workspace cluster-resources repository as values source'() {
		def strategy = createStrategy()
		File valuesYaml = File.createTempFile('values', 'yaml')

		strategy.deployFeature('repoURL',
			'repoName',
			'chartName',
			'version',
			'namespace',
			'releaseName',
			valuesYaml.toPath())

		def argoCdApplicationYaml = new File("$localTempDir/apps/argocd/applications/releaseName.yaml")
		def result = new YamlSlurper().parse(argoCdApplicationYaml)
		def sources = result['spec']['sources'] as List

		assertThat(sources[1]['repoURL'])
			.isEqualTo('http://scmm.scm-manager.svc.cluster.local/scm/repo/argocd/cluster-resources.git')

		assertThat(sources[1]['path'])
			.isEqualTo('apps/repoName')
	}

	private ArgoCdApplicationStrategy createStrategy(boolean argocdOperator = false) {
		Config config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-',
			gitName: 'Cloudogu',
			gitEmail: 'hello@cloudogu.com'),
			scm: new ScmTenantSchema(scmManager: new ScmManagerTenantConfig(username: 'dont-care-username',
				password: 'dont-care-password')),
			features: new Config.FeaturesSchema(argocd: new Config.ArgoCDSchema(operator: argocdOperator)))

		GitProvider gitProvider = new ScmManagerMock()
		def repoProvider = new TestGitRepoFactory(config, new FileSystemUtils()) {
			@Override
			GitRepo create(String repoTarget, GitProvider provider) {
				def repo = super.create(repoTarget, provider)
				localTempDir = new File(repo.getAbsoluteLocalRepoTmpDir())

				return repo
			}
		}

		GitRepo clusterResourcesRepo = repoProvider.create('argocd/cluster-resources',
			gitProvider)

		RepositoryWorkspace repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo)

		repositoryProvisioning = mock(RepositoryProvisioning)
		when(repositoryProvisioning.provideWorkspace()).thenReturn(repositoryWorkspace)

		return new ArgoCdApplicationStrategy(new ContextBuilder(config).build(),
			new FileSystemUtils(),
			repositoryProvisioning)
	}
}