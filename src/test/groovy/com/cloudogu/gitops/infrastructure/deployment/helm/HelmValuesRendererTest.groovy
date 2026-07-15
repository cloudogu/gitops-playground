package com.cloudogu.gitops.infrastructure.deployment.helm

import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Files
import java.nio.file.Path
import groovy.transform.CompileStatic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@CompileStatic
class HelmValuesRendererTest {

	@TempDir
	Path tempDir

	private final FileSystemUtils fileSystemUtils =
		new FileSystemUtils()

	private final HelmValuesRenderer renderer =
		new HelmValuesRenderer(fileSystemUtils)

	@Test
	void 'returns configured values when no values path is given'() {
		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: [replicaCount: 2,
			                                         service     : [type: 'NodePort']])

		Map<String, Object> result = renderer.render(helmConfig,
			null,
			[:])

		assertThat(result).isEqualTo([replicaCount: 2,
		                              service     : [type: 'NodePort']])
	}

	@Test
	void 'reads plain YAML values file'() {
		Path valuesFile = tempDir.resolve('values.yaml')

		Files.writeString(valuesFile,
			'''
			replicaCount: 1
			service:
			  type: ClusterIP
			  port: 8080
			'''.stripIndent())

		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: [:])

		Map<String, Object> result = renderer.render(helmConfig,
			valuesFile.toString(),
			[:])

		assertThat(result['replicaCount']).isEqualTo(1)

		assertThat(result['service'] as Map)
			.containsEntry('type', 'ClusterIP')
			.containsEntry('port', 8080)
	}

	@Test
	void 'renders Freemarker values template with supplied data'() {
		Path templateFile =
			tempDir.resolve('values.ftl.yaml')

		Files.writeString(templateFile,
			'''
			image:
			  repository: ${image.repository}
			  tag: "${image.tag}"
			ingress:
			  enabled: ${ingressEnabled?c}
			  host: ${host}
			'''.stripIndent())

		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: [:])

		Map<String, Object> result = renderer.render(helmConfig,
			templateFile.toString(),
			[image         : [repository: 'registry.example.org/application',
			                  tag       : '1.2.3'],
			 ingressEnabled: true,
			 host          : 'application.example.org'] as Map<String, Object>)

		assertThat(result['image'] as Map)
			.containsEntry('repository',
				'registry.example.org/application')
			.containsEntry('tag', '1.2.3')

		assertThat(result['ingress'] as Map)
			.containsEntry('enabled', true)
			.containsEntry('host',
				'application.example.org')
	}

	@Test
	void 'configured values override values from YAML file recursively'() {
		Path valuesFile = tempDir.resolve('values.yaml')

		Files.writeString(valuesFile,
			'''
			replicaCount: 1
			service:
			  type: ClusterIP
			  port: 8080
			resources:
			  limits:
			    cpu: 500m
			    memory: 512Mi
			'''.stripIndent())

		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: [replicaCount: 3,
			                                         service     : [type: 'NodePort'],
			                                         resources   : [limits: [memory: '1Gi']]])

		Map<String, Object> result = renderer.render(helmConfig,
			valuesFile.toString(),
			[:])

		assertThat(result['replicaCount']).isEqualTo(3)

		assertThat(result['service'] as Map)
			.containsEntry('type', 'NodePort')
			.containsEntry('port', 8080)

		Map limits =
			((result['resources'] as Map)['limits']) as Map

		assertThat(limits)
			.containsEntry('cpu', '500m')
			.containsEntry('memory', '1Gi')
	}

	@Test
	void 'configured values override rendered Freemarker values'() {
		Path templateFile =
			tempDir.resolve('values.ftl.yaml')

		Files.writeString(templateFile,
			'''
			replicaCount: ${replicaCount}
			service:
			  type: ClusterIP
			  port: 8080
			'''.stripIndent())

		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: [replicaCount: 4,
			                                         service     : [type: 'LoadBalancer']])

		Map<String, Object> result = renderer.render(helmConfig,
			templateFile.toString(),
			[replicaCount: 1] as Map<String, Object>)

		assertThat(result['replicaCount']).isEqualTo(4)

		assertThat(result['service'] as Map)
			.containsEntry('type', 'LoadBalancer')
			.containsEntry('port', 8080)
	}

	@Test
	void 'returns empty values when no path and no configured values exist'() {
		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: null)

		Map<String, Object> result = renderer.render(helmConfig,
			null,
			[:])

		assertThat(result).isEmpty()
	}

	@Test
	void 'handles empty rendered template'() {
		Path templateFile =
			tempDir.resolve('empty.ftl.yaml')

		Files.writeString(templateFile,
			'''
			<#if enabled>
			replicaCount: 1
			</#if>
			'''.stripIndent())

		Config.HelmConfigWithValues helmConfig =
			new Config.HelmConfigWithValues(values: [:])

		Map<String, Object> result = renderer.render(helmConfig,
			templateFile.toString(),
			[enabled: false] as Map<String, Object>)

		assertThat(result).isEmpty()
	}
}