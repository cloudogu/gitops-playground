package com.cloudogu.gitops

import com.cloudogu.gitops.config.Config
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static com.cloudogu.gitops.config.Config.*

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

    @Test
    void 'get active namespaces correctly'() {
        config.registry.active = true
        config.jenkins.active = true
        config.features.monitoring.active = true
        config.features.argocd.active = true
        config.content.examples = true
        config.features.ingressNginx.active = true
        config.application.namePrefix = 'test1-'
        List<String> namespaceList = new ArrayList<>(Arrays.asList(
                "test1-argocd",
                "test1-example-apps-staging",
                "test1-example-apps-production",
                "test1-ingress-nginx",
                "test1-monitoring",
                "test1-scm-manager",
                "test1-registry",
                "test1-jenkins"
        ))
        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)
        application.setNamespaceListToConfig(config)
        assertThat(config.application.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
    }
    @Test
    void 'get active namespaces correctly in Openshift'() {
        config.registry.active = true
        config.jenkins.active = true
        config.features.monitoring.active = true
        config.features.argocd.active = true
        config.content.examples = true
        config.features.ingressNginx.active = true
        config.application.namePrefix = 'test1-'
        config.application.openshift = true
        List<String> namespaceList = new ArrayList<>(Arrays.asList(
                "test1-argocd",
                "test1-example-apps-staging",
                "test1-example-apps-production",
                "test1-ingress-nginx",
                "test1-monitoring",
                "test1-scm-manager",
                "test1-registry",
                "test1-jenkins"
        ))
        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)
        application.setNamespaceListToConfig(config)
        assertThat(config.application.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
    }
}