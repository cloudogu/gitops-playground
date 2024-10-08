package com.cloudogu.gitops

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.config.schema.Schema
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static com.cloudogu.gitops.config.schema.Schema.*

class ApplicationTest {

    Schema config = new Schema(
            scmm: new ScmmSchema(url: 'http://localhost'))

    @Test
    void 'feature\'s ordering is correct'() {
        def application = ApplicationContext.run()
                .registerSingleton(config)
                .registerSingleton(new Configuration(config.toMap()))
                .getBean(Application)
        def features = application.features.collect { it.class.simpleName }

        assertThat(features).isEqualTo(["Registry", "ScmManager", "Jenkins", "Content", "ArgoCD", "IngressNginx", "CertManager", "Mailhog", "PrometheusStack", "ExternalSecretsOperator", "Vault"])
    }
}
