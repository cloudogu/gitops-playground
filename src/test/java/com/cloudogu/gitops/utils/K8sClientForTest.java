package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

public class K8sClientForTest extends K8sClient {

	public K8sClientForTest() {
		super();
		setClient(new KubernetesMockServer().createClient());
		sleepTimeMillis = 1;
	}
}
