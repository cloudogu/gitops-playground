package com.cloudogu.gitops.utils

import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer

class K8sClientForTest extends K8sClient {

    K8sClientForTest() {
        super()
        this.client = new KubernetesMockServer().createClient()
        this.SLEEPTIME = 1
    }
}