package com.cloudogu.gitops.utils

import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.kubernetes.api.K8sClient

import jakarta.inject.Provider

class K8sClientForTest extends K8sClient {
    CommandExecutorForTest commandExecutorForTest

    K8sClientForTest(Config config, CommandExecutorForTest commandExecutor = new CommandExecutorForTest()) {
        super(commandExecutor, new FileSystemUtils(), new Provider<Config>() {
            @Override
            Config get() {
                return config
            }
        })
        this.k8sJavaApiClient.client = new KubernetesMockServer().createClient()
        commandExecutorForTest = commandExecutor
        this.SLEEPTIME = 1
    }
}