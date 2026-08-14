package com.cloudogu.gitops.application

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.DeploymentOrchestrator
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.*

class ApplicationTest {

    private Config config = new Config()

    @Test
    void 'validates git configuration before building deployment context'() {
        def contextBuilder = mock(ContextBuilder)
        def k8sClient = mock(K8sClient)
        def gitHandler = mock(GitHandler)
        def repositoryProvisioning = mock(RepositoryProvisioning)
        def deploymentOrchestrator = mock(DeploymentOrchestrator)
        def context = buildContext()
        def workspace = mock(RepositoryWorkspace)

        when(contextBuilder.build()).thenReturn(context)
        when(deploymentOrchestrator.getTools()).thenReturn([])
        when(repositoryProvisioning.provideWorkspace(context)).thenReturn(workspace)

        def application = new Application(
                contextBuilder,
                k8sClient,
                gitHandler,
                repositoryProvisioning,
                deploymentOrchestrator)

        application.start()

        def order = inOrder(gitHandler, contextBuilder)
        order.verify(gitHandler).validate()
        order.verify(contextBuilder).build()
    }

    @Test
    void 'feature\'s ordering is correct'() {
        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)

        def features = application.tools.collect { it.class.simpleName }

        assertThat(features).isEqualTo(['ScmManager', 'Registry', 'ArgoCD', 'Ingress', 'CertManager', 'Jenkins', 'Monitoring', 'ExternalSecretsOperator', 'Vault', 'ContentLoader'])
    }

    @Test
    void 'get active namespaces correctly'() {
        config.registry.active = true
        config.jenkins.active = true
        config.features.monitoring.active = true
        config.features.argocd.active = true
        config.features.ingress.active = true
        config.application.namePrefix = 'test1-'
        config.content.namespaces = ['${config.application.namePrefix}example-apps-staging',
                                     '${config.application.namePrefix}example-apps-production']

        List<String> namespaceList = new ArrayList<>(Arrays.asList(
                "test1-argocd",
                "test1-example-apps-staging",
                "test1-example-apps-production",
                "test1-" + config.features.ingress.ingressNamespace,
                "test1-monitoring",
                "test1-registry",
                "test1-jenkins"
        ))

        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)

        application.setNamespaceListToConfig(buildContext())

        assertThat(config.application.namespaces.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
    }

    @Test
    void 'get active namespaces correctly in Openshift'() {
        config.registry.active = true
        config.jenkins.active = true
        config.features.monitoring.active = true
        config.features.argocd.active = true
        config.features.ingress.active = true
        config.application.namePrefix = 'test1-'
        config.application.openshift = true
        config.content.namespaces = ['${config.application.namePrefix}example-apps-staging',
                                     '${config.application.namePrefix}example-apps-production']

        List<String> namespaceList = new ArrayList<>(Arrays.asList(
                "test1-argocd",
                "test1-example-apps-staging",
                "test1-example-apps-production",
                "test1-" + config.features.ingress.ingressNamespace,
                "test1-monitoring",
                "test1-registry",
                "test1-jenkins"
        ))

        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)

        application.setNamespaceListToConfig(buildContext())

        assertThat(config.application.namespaces.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
    }

    @Test
    void 'handles content namespaces without template'() {
        config.content.namespaces = ['example-apps-staging',
                                     'example-apps-production']

        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)

        application.setNamespaceListToConfig(buildContext())

        assertThat(config.application.namespaces.getActiveNamespaces()).containsAll([
                "example-apps-staging",
                "example-apps-production"
        ])
    }

    @Test
    void 'handles empty content namespaces'() {
        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)

        application.setNamespaceListToConfig(buildContext())

        // No exception == happy
    }

    @Test
    void 'get active namespaces correctly in Openshift if jenkins and scm are external'() {
        config.registry.active = true
        config.jenkins.active = true
        config.jenkins.internal = false
        config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig()
        config.scm.scmManager.internal = false
        config.features.monitoring.active = true
        config.features.argocd.active = true
        config.features.ingress.active = true
        config.application.namePrefix = 'test1-'
        config.application.openshift = true
        config.content.namespaces = ['${config.application.namePrefix}example-apps-staging',
                                     '${config.application.namePrefix}example-apps-production']

        List<String> namespaceList = new ArrayList<>(Arrays.asList(
                "test1-argocd",
                "test1-example-apps-staging",
                "test1-example-apps-production",
                "test1-" + config.features.ingress.ingressNamespace,
                "test1-monitoring",
                "test1-registry"
        ))

        def application = ApplicationContext.run()
                .registerSingleton(config)
                .getBean(Application)

        application.setNamespaceListToConfig(buildContext())

        assertThat(config.application.namespaces.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
    }

    private DeploymentContext buildContext() {
        return new ContextBuilder(config).build()
    }
}