package com.cloudogu.gitops

import com.cloudogu.gitops.config.Configuration
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ApplicationTest {
    Map config = [
            application: [
                    username: "the-user",
                    password: "the-password"
            ],
            scmm: [
                    internal: true
            ]
    ]

    @Test
    void 'feature\'s ordering is correct'() {
        def application = ApplicationContext.run()
                .registerSingleton(new Configuration(config))
                .getBean(Application)
        def features = application.features.collect { it.class.simpleName }

        assertThat(features).isEqualTo(["ArgoCD", "Mailhog", "PrometheusStack", "ExternalSecretsOperator", "Vault"])
    }
}