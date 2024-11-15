package com.cloudogu.gitops.jenkins

import com.cloudogu.gitops.config.Config
import jakarta.inject.Singleton

@Singleton
class JenkinsConfigurationAdapter extends JenkinsConfiguration {
    JenkinsConfigurationAdapter(Config config) {
        super(
                config.jenkins.url,
                config.jenkins.username,
                config.jenkins.password,
                config.application.insecure
        )
    }
}
