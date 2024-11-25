package com.cloudogu.gitops

import com.cloudogu.gitops.config.Config
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static com.cloudogu.gitops.config.Config.ScmmSchema
import static org.assertj.core.api.Assertions.assertThat

class ApplicationTest {

    Config config = new Config(
            scmm: new ScmmSchema(url: 'http://localhost'))

    @Test
    void 'feature\'s ordering is correct'() {
        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)
        def features = application.features.collect { it.class.simpleName }

        assertThat(features).isEqualTo(["Registry", "ScmManager", "Jenkins", "Content", "ArgoCD", "IngressNginx", "CertManager", "Mailhog", "PrometheusStack", "ExternalSecretsOperator", "Vault"])
    }
}
