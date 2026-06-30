package com.cloudogu.gitops.tools.common

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@EnableKubernetesMockClient(crud = true)
class ToolTest {
	Config config = new Config(application: new Config.ApplicationSchema(namePrefix: "foo-"))

	K8sClient k8sClient
	KubernetesClient client

	@BeforeEach
	void init() {
		k8sClient = new K8sClient()
		k8sClient.client = client
	}

	@Test
	void 'Image pull secrets are create automatically'() {
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'
		config.registry.url = 'url'
		config.registry.readOnlyUsername = 'ROuser'
		config.registry.readOnlyPassword = 'ROpw'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		createFeatureWithImage().install()
	}

	protected ToolWithImageForTest createFeatureWithImage() {
		Tool feature = new ToolWithImageForTest()
		feature.context = new ContextBuilder(config).build()
		feature.k8sClient = k8sClient
		feature.namespace = 'foo-my-ns'
		feature
	}

	@Test
	void 'Image pull secrets: Falls back to using readOnly credentials and URL '() {
		config.registry.createImagePullSecrets = true
		config.registry.url = 'url'
		config.registry.readOnlyUsername = 'ROuser'
		config.registry.readOnlyPassword = 'ROpw'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		createFeatureWithImage().install()
	}

	@Test
	void 'Image pull secrets: Falls back to using credentials and URL '() {
		config.registry.createImagePullSecrets = true
		config.registry.url = 'url'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		createFeatureWithImage().install()
	}

	class ToolWithImageForTest extends Tool implements ToolWithImage {

		String namespace
		K8sClient k8sClient

		@Override
		boolean isEnabled() {
			return true
		}
	}
}