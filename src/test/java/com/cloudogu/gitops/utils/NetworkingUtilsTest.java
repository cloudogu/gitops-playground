package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkingUtilsTest {

	private final K8sClient k8sClient = mock(K8sClient.class);
	private final CommandExecutorForTest commandExecutor = new CommandExecutorForTest();
	private final NetworkingUtils networkingUtils = new NetworkingUtils(k8sClient, commandExecutor);

	@Test
	void clusterBindAddressReturnsBindAddressForExternalCluster() {
		String internalNodeIp = "1.2.3.4";
		String localIp = "5.6.7.8";
		when(k8sClient.waitForInternalNodeIp()).thenReturn(internalNodeIp);
		commandExecutor.enqueueOutput(new CommandExecutor.Output(
			"",
			"1.0.0.0 via w.x.y.z dev someDevice src " + localIp + " uid 1000",
			0
		));

		String actualBindAddress = networkingUtils.findClusterBindAddress();

		assertThat(actualBindAddress).isEqualTo(internalNodeIp);
	}

	@Test
	void clusterBindAddressReturnsLocalhostWhenNodeIpAndLocalIpAreEqual() {
		String internalNodeIp = networkingUtils.getLocalAddress();
		assertThat(internalNodeIp).isNotEmpty();

		when(k8sClient.waitForInternalNodeIp()).thenReturn(internalNodeIp);

		String actualBindAddress = networkingUtils.findClusterBindAddress();

		assertThat(actualBindAddress).isEqualTo("localhost");
	}

	@Test
	void clusterBindAddressFailsWhenNoPotentialBindAddress() {
		when(k8sClient.waitForInternalNodeIp()).thenReturn("");
		commandExecutor.enqueueOutput(new CommandExecutor.Output(
			"",
			"1.0.0.0 via w.x.y.z dev someDevice src 1.2.3.4 uid 1000",
			0
		));

		RuntimeException exception = assertThrows(RuntimeException.class, networkingUtils::findClusterBindAddress);

		assertThat(exception.getMessage()).isEqualTo(
			"Could not connect to kubernetes cluster: no cluster bind address"
		);
	}
}
