package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.scm.ScmHandler
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.FileSystemUtils

class MultiTenantTest {

    Config testConfig = Config.fromMap([
            application: [
            ]
    ])

    private ScmHandler createMultiTenant() {
        new ScmHandler(testConfig, commandExecutor, new FileSystemUtils() {
        }, new HelmStrategy(config, helmClient), k8sClient, networkingUtils)
    }
}