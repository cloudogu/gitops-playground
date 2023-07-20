package com.cloudogu.gitops.jenkins

import jakarta.inject.Singleton

@Singleton
class PrometheusConfigurator {
    private final ApiClient apiClient

    PrometheusConfigurator(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void enableAuthentication() {
        def result = apiClient.runScript("""
            import org.jenkinsci.plugins.prometheus.config.*
            
            def config = Jenkins.instance.getDescriptor(PrometheusConfiguration)
            config.setUseAuthenticatedEndpoint(true)
            
            print(config.useAuthenticatedEndpoint)
        """)

        if (result != "true") {
            throw new RuntimeException("Cannot enable authentication for prometheus: $result")
        }
    }
}
