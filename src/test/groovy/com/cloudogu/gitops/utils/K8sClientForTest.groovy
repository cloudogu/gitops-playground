package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Configuration
import jakarta.inject.Provider

class K8sClientForTest extends K8sClient {
    CommandExecutorForTest commandExecutorForTest
    
    K8sClientForTest(Map config, CommandExecutorForTest commandExecutor = new CommandExecutorForTest()) {
        super(commandExecutor, new FileSystemUtils(), new Provider<Configuration>() {
            @Override
            Configuration get() {
                new Configuration(config)
            }
        })
        commandExecutorForTest = commandExecutor
    }
}
