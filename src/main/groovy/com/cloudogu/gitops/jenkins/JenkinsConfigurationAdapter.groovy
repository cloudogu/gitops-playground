package com.cloudogu.gitops.jenkins

import com.cloudogu.gitops.config.Configuration
import jakarta.inject.Singleton

@Singleton
class JenkinsConfigurationAdapter extends JenkinsConfiguration {
    JenkinsConfigurationAdapter(Configuration configuration) {
        super(
                configuration.config.jenkins['url'] as String,
                configuration.config.jenkins['username'] as String,
                configuration.config.jenkins['password'] as String,
                configuration.config.application['insecure'] as Boolean
        )
    }
}
