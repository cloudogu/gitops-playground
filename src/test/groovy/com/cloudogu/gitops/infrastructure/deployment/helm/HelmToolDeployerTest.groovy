package com.cloudogu.gitops.infrastructure.deployment.helm

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Path
import groovy.transform.CompileStatic

import freemarker.template.TemplateModel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@CompileStatic
@ExtendWith(MockitoExtension)
class HelmToolDeployerTest {

	private static final String TOOL_NAME =
		'cert-manager'

	private static final String RELEASE_NAME =
		'cert-manager'

	private static final String NAMESPACE =
		'my-prefix-cert-manager'

	private static final String REPOSITORY_URL =
		'https://charts.jetstack.io'

	private static final String CHART =
		'cert-manager'

	private static final String VERSION =
		'1.19.4'

	@Mock
	Deployer deployer

	@Mock
	FileSystemUtils fileSystemUtils

	@Mock
	AirGappedUtils airGappedUtils

	@Mock
	GitHandler gitHandler

	@Mock
	GitProvider resourcesScm

	@Mock
	HelmValuesRenderer helmValuesRenderer

	@Mock
	RepositoryWorkspace repositoryWorkspace

	private Config config
	private DeploymentContext context
	private HelmToolDeployer helmToolDeployer

	@BeforeEach
	void setUp() {
		config = Config.fromMap([application: [namePrefix: 'my-prefix-']])

		context = new ContextBuilder(config).build()

		helmToolDeployer = new HelmToolDeployer(deployer,
			fileSystemUtils,
			airGappedUtils,
			gitHandler,
			helmValuesRenderer)
	}

	@Test
	void 'deploys chart from configured Helm repository'() {
		HelmToolDeploymentRequest request =
			createRequest(false)

		Map<String, Object> renderedValues = [replicaCount: 2] as Map<String, Object>

		Path valuesFile = Path.of('/tmp/values.yaml')

		when(helmValuesRenderer.render(eq(request.helmConfig),
			eq(request.helmValuesPath),
			any(Map))).thenReturn(renderedValues)

		when(fileSystemUtils.writeTempFile(renderedValues)).thenReturn(valuesFile)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)

		verify(deployer).deployFeature(REPOSITORY_URL,
			TOOL_NAME,
			CHART,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			valuesFile,
			RepoType.HELM,
			false,
			context,
			repositoryWorkspace)

		verifyNoInteractions(airGappedUtils)
	}

	@Test
	void 'adds config and statics to template data'() {
		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				RELEASE_NAME,
				NAMESPACE,
				createHelmConfig(),
				'templates/values.ftl.yaml',
				[customValue: 'value'] as Map<String, Object>,
				false)

		when(helmValuesRenderer.render(any(),
			any(),
			any())).thenReturn([:] as Map<String, Object>)

		when(fileSystemUtils.writeTempFile(any(Map))).thenReturn(Path.of('/tmp/values.yaml'))

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)

		ArgumentCaptor<Map> templateDataCaptor =
			ArgumentCaptor.forClass(Map)

		verify(helmValuesRenderer).render(eq(request.helmConfig),
			eq(request.helmValuesPath),
			templateDataCaptor.capture())

		Map<String, Object> templateData =
			templateDataCaptor.value as Map<String, Object>

		assertThat(templateData)
			.containsEntry('customValue', 'value')
			.containsEntry('config', config)
			.containsKey('statics')

		assertThat(templateData['statics'])
			.isInstanceOf(TemplateModel)
	}

	@Test
	void 'writes rendered values to temporary file before deployment'() {
		HelmToolDeploymentRequest request =
			createRequest(false)

		Map<String, Object> renderedValues = [service: [type: 'NodePort']] as Map<String, Object>

		Path valuesFile = Path.of('/tmp/rendered.yaml')

		when(helmValuesRenderer.render(any(),
			any(),
			any())).thenReturn(renderedValues)

		when(fileSystemUtils.writeTempFile(renderedValues)).thenReturn(valuesFile)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)

		verify(fileSystemUtils)
			.writeTempFile(renderedValues)

		verify(deployer).deployFeature(any(),
			any(),
			any(),
			any(),
			any(),
			any(),
			eq(valuesFile),
			any(),
			anyBoolean(),
			eq(context),
			eq(repositoryWorkspace))
	}

	@Test
	void 'passes bootstrap flag to Deployer'() {
		HelmToolDeploymentRequest request =
			createRequest(true)

		Path valuesFile = Path.of('/tmp/values.yaml')

		when(helmValuesRenderer.render(any(),
			any(),
			any())).thenReturn([:] as Map<String, Object>)

		when(fileSystemUtils.writeTempFile(any(Map))).thenReturn(valuesFile)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)

		verify(deployer).deployFeature(REPOSITORY_URL,
			TOOL_NAME,
			CHART,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			valuesFile,
			RepoType.HELM,
			true,
			context,
			repositoryWorkspace)
	}

	@Test
	void 'does not modify request template data'() {
		Map<String, Object> originalTemplateData =
			[customValue: 'original'] as Map<String, Object>

		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				RELEASE_NAME,
				NAMESPACE,
				createHelmConfig(),
				'templates/values.ftl.yaml',
				originalTemplateData,
				false)

		when(helmValuesRenderer.render(any(),
			any(),
			any())).thenReturn([:] as Map<String, Object>)

		when(fileSystemUtils.writeTempFile(any(Map))).thenReturn(Path.of('/tmp/values.yaml'))

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)

		assertThat(originalTemplateData)
			.isEqualTo([customValue: 'original'])
	}

	private HelmToolDeploymentRequest createRequest(boolean bootstrapWithHelm) {
		return new HelmToolDeploymentRequest(TOOL_NAME,
			RELEASE_NAME,
			NAMESPACE,
			createHelmConfig(),
			'templates/values.ftl.yaml',
			[:],
			bootstrapWithHelm)
	}

	private Config.HelmConfigWithValues createHelmConfig() {
		return new Config.HelmConfigWithValues(repoURL: REPOSITORY_URL,
			chart: CHART,
			version: VERSION,
			values: [:])
	}
}