package com.cloudogu.gitops.scm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.scm.MultiTenant
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.FileSystemUtils

class MultiTenantTest {

    Config testConfig = Config.fromMap([
            application: [
            ]
    ])

    private MultiTenant createMultiTenant() {
        new MultiTenant(testConfig, commandExecutor, new FileSystemUtils() {
        }, new HelmStrategy(config, helmClient), k8sClient, networkingUtils)
    }
}