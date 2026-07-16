package com.cloudogu.gitops.infrastructure.jenkins;

import jakarta.inject.Singleton;

@Singleton
public class PrometheusConfigurator {
    private final JenkinsApiClient apiClient;

    public PrometheusConfigurator(JenkinsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void enableAuthentication() {
        String result = apiClient.runScript(
                "import org.jenkinsci.plugins.prometheus.config.*\n" +
                "\n" +
                "def config = Jenkins.instance.getDescriptor(PrometheusConfiguration)\n" +
                "config.setUseAuthenticatedEndpoint(true)\n" +
                "\n" +
                "print(config.useAuthenticatedEndpoint)\n"
        );

        if (!"true".equals(result)) {
            throw new RuntimeException("Cannot enable authentication for prometheus: " + result);
        }
    }
}
