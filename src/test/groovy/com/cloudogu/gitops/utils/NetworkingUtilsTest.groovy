package com.cloudogu.gitops.utils

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

import com.cloudogu.gitops.kubernetes.api.K8sClient

import org.junit.jupiter.api.Test

class NetworkingUtilsTest {

	K8sClient k8sClient = mock(K8sClient)
	CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
	NetworkingUtils networkingUtils = new NetworkingUtils(k8sClient, commandExecutor)

	@Test
	void 'clusterBindAddress: returns bind address for external cluster'() {
		def internalNodeIp = "1.2.3.4"
		def localIp = "5.6.7.8"
		when(k8sClient.waitForInternalNodeIp()).thenReturn(internalNodeIp)
		commandExecutor.enqueueOutput(new CommandExecutor.Output('',
		                                                         "1.0.0.0 via w.x.y.z dev someDevice src ${localIp} uid 1000", 0))

		def actualBindAddress = networkingUtils.findClusterBindAddress()

		assertThat(actualBindAddress).isEqualTo(internalNodeIp)
	}

	@Test
	void 'clusterBindAddress: returns localhost when node IP and local IP are equal'() {
		def internalNodeIp = networkingUtils.localAddress
		assertThat(internalNodeIp).isNotEmpty()

		when(k8sClient.waitForInternalNodeIp()).thenReturn(internalNodeIp)

		def actualBindAddress = networkingUtils.findClusterBindAddress()

		assertThat(actualBindAddress).isEqualTo('localhost')
	}

	@Test
	void 'clusterBindAddress: fails when no potential bind address'() {
		when(k8sClient.waitForInternalNodeIp()).thenReturn('')
		commandExecutor.enqueueOutput(new CommandExecutor.Output('',
		                                                         "1.0.0.0 via w.x.y.z dev someDevice src 1.2.3.4 uid 1000", 0))

		def exception = shouldFail(RuntimeException) {
			networkingUtils.findClusterBindAddress()
		}
		assertThat(exception.message).isEqualTo('Could not connect to kubernetes cluster: no cluster bind address')
	}

	@Test
	void 'get hosts'() {
		assertThat(NetworkingUtils.getHost("https://example.com")).isEqualTo("example.com")
		assertThat(NetworkingUtils.getHost("http://example.com")).isEqualTo("example.com")
		assertThat(NetworkingUtils.getHost("")).isEqualTo("")
		assertThat(NetworkingUtils.getHost("example.com")).isEqualTo("example.com")

		assertThat(NetworkingUtils.getHost("http://example.com/bla")).isEqualTo("example.com/bla")
		assertThat(NetworkingUtils.getHost("http://example.com:9090/bla")).isEqualTo("example.com:9090/bla")
		assertThat(NetworkingUtils.getHost("example.com/bla")).isEqualTo("example.com/bla")
		assertThat(NetworkingUtils.getHost("example.com:9090/bla")).isEqualTo("example.com:9090/bla")
	}

	@Test
	void 'get protocols'() {
		assertThat(NetworkingUtils.getProtocol("https://example.com")).isEqualTo("https");
		assertThat(NetworkingUtils.getProtocol("http://example.com")).isEqualTo("http");
		assertThat(NetworkingUtils.getProtocol("ftp://example.com")).isEqualTo("");
		assertThat(NetworkingUtils.getProtocol("example.com")).isEqualTo("");
		assertThat(NetworkingUtils.getProtocol("")).isEqualTo("")
	}
}