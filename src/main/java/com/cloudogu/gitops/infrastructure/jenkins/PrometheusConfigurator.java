package com.cloudogu.gitops.infrastructure.jenkins;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class PrometheusConfigurator {
    private final JenkinsApiClient apiClient;

    public void enableAuthentication() {
        String result = apiClient.runScript("""
            import org.jenkinsci.plugins.prometheus.config.*

            def config = Jenkins.instance.getDescriptor(PrometheusConfiguration)
            config.setUseAuthenticatedEndpoint(true)

            print(config.useAuthenticatedEndpoint)
            """);

        if (!"true".equals(result)) {
            throw new RuntimeException("Cannot enable authentication for prometheus: " + result);
        }
    }
}
