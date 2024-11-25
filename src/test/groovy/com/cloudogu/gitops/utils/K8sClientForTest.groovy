package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
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
        commandExecutorForTest = commandExecutor
    }
}
