package com.cloudogu.gitops.tools.common

import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient

import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@EnableKubernetesMockClient(crud = true)
class ImagePullSecretCreatorTest {

	private static final String NAMESPACE = 'foo-my-ns'
	private static final String SECRET_NAME = 'proxy-registry'

	KubernetesClient client
	K8sClient k8sClient
	ImagePullSecretCreator imagePullSecretCreator

	@BeforeEach
	void init() {
		k8sClient = new K8sClient()
		k8sClient.client = client
		imagePullSecretCreator = new ImagePullSecretCreator(k8sClient)
	}

	@Test
	void 'does not create image pull secret when disabled'() {
		Config config = new Config()
		config.registry.createImagePullSecrets = false

		imagePullSecretCreator.createIfRequired(config, NAMESPACE)

		assertThat(secret()).isNull()
	}

	@Test
	void 'creates image pull secret with proxy credentials when proxy is configured'() {
		Config config = new Config()
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'
		config.registry.url = 'url'
		config.registry.readOnlyUsername = 'ROuser'
		config.registry.readOnlyPassword = 'ROpw'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		imagePullSecretCreator.createIfRequired(config, NAMESPACE)

		Secret secret = secret()

		assertThat(secret).isNotNull()
		assertThat(secret.type).isEqualTo('kubernetes.io/dockerconfigjson')
		assertDockerConfigContains(secret, 'proxy-url', 'proxy-user', 'proxy-pw')
	}

	@Test
	void 'creates image pull secret with read only credentials when proxy credentials are not configured'() {
		Config config = new Config()
		config.registry.createImagePullSecrets = true
		config.registry.url = 'url'
		config.registry.readOnlyUsername = 'ROuser'
		config.registry.readOnlyPassword = 'ROpw'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		imagePullSecretCreator.createIfRequired(config, NAMESPACE)

		Secret secret = secret()

		assertThat(secret).isNotNull()
		assertThat(secret.type).isEqualTo('kubernetes.io/dockerconfigjson')
		assertDockerConfigContains(secret, 'url', 'ROuser', 'ROpw')
	}

	@Test
	void 'creates image pull secret with default credentials when read only credentials are not configured'() {
		Config config = new Config()
		config.registry.createImagePullSecrets = true
		config.registry.url = 'url'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		imagePullSecretCreator.createIfRequired(config, NAMESPACE)

		Secret secret = secret()

		assertThat(secret).isNotNull()
		assertThat(secret.type).isEqualTo('kubernetes.io/dockerconfigjson')
		assertDockerConfigContains(secret, 'url', 'user', 'pw')
	}

	@Test
	void 'creates namespace before creating image pull secret'() {
		Config config = new Config()
		config.registry.createImagePullSecrets = true
		config.registry.url = 'url'
		config.registry.username = 'user'
		config.registry.password = 'pw'

		imagePullSecretCreator.createIfRequired(config, NAMESPACE)

		assertThat(client.namespaces().withName(NAMESPACE).get()).isNotNull()
		assertThat(secret()).isNotNull()
	}

	private Secret secret() {
		return client.secrets()
			.inNamespace(NAMESPACE)
			.withName(SECRET_NAME)
			.get()
	}

	private static void assertDockerConfigContains(Secret secret,
		String expectedUrl,
		String expectedUsername,
		String expectedPassword) {
		String dockerConfigJson = decodeSecretValue(secret, '.dockerconfigjson')

		assertThat(dockerConfigJson).contains(expectedUrl)
		assertThat(dockerConfigJson).contains(expectedUsername)
		assertThat(dockerConfigJson).contains(expectedPassword)
	}

	private static String decodeSecretValue(Secret secret, String key) {
		if (secret.stringData?.containsKey(key)) {
			return secret.stringData[key]
		}

		if (secret.data?.containsKey(key)) {
			return new String(Base64.decoder.decode(secret.data[key]))
		}

		return null
	}
}