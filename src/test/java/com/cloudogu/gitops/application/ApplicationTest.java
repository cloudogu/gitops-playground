package com.cloudogu.gitops.application;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.DeploymentOrchestrator;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryProvisioning;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationTest {

	private final Config config = new Config();

	@Test
	void validatesGitConfigurationBeforeBuildingDeploymentContext() {
		ContextBuilder contextBuilder = mock(ContextBuilder.class);
		K8sClient k8sClient = mock(K8sClient.class);
		GitHandler gitHandler = mock(GitHandler.class);
		RepositoryProvisioning repositoryProvisioning = mock(RepositoryProvisioning.class);
		DeploymentOrchestrator deploymentOrchestrator = mock(DeploymentOrchestrator.class);
		DeploymentContext context = buildContext();
		RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);

		when(contextBuilder.build()).thenReturn(context);
		when(deploymentOrchestrator.getTools()).thenReturn(List.of());
		when(repositoryProvisioning.provideWorkspace(context)).thenReturn(workspace);

		Application application = new Application(
			config,
			contextBuilder,
			k8sClient,
			gitHandler,
			repositoryProvisioning,
			deploymentOrchestrator
		);

		application.start();

		var order = inOrder(gitHandler, contextBuilder);
		order.verify(gitHandler).validate();
		order.verify(contextBuilder).build();
	}

	@Test
	void featuresOrderingIsCorrect() {
		Application application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application.class);

		List<String> features = application.getTools().stream()
			.map(tool -> tool.getClass().getSimpleName())
			.collect(Collectors.toList());

		assertThat(features).isEqualTo(List.of(
			"ScmManager",
			"Registry",
			"ArgoCD",
			"Ingress",
			"CertManager",
			"Jenkins",
			"Monitoring",
			"ExternalSecretsOperator",
			"Vault",
			"ContentLoader"
		));
	}

	@Test
	void getActiveNamespacesCorrectly() {
		config.getRegistry().setActive(true);
		config.getJenkins().setActive(true);
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getIngress().setActive(true);
		config.getApplication().setNamePrefix("test1-");
		config.getContent().setNamespaces(List.of(
			"${config.application.namePrefix}example-apps-staging",
			"${config.application.namePrefix}example-apps-production"
		));

		List<String> namespaceList = new ArrayList<>(Arrays.asList(
			"test1-argocd",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-" + config.getFeatures().getIngress().getIngressNamespace(),
			"test1-monitoring",
			"test1-registry",
			"test1-jenkins"
		));

		Application application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application.class);

		application.setNamespaceListToConfig(buildContext());

		assertThat(config.getApplication().getNamespaces().getActiveNamespaces())
			.containsExactlyInAnyOrderElementsOf(namespaceList);
	}

	@Test
	void getActiveNamespacesCorrectlyInOpenshift() {
		config.getRegistry().setActive(true);
		config.getJenkins().setActive(true);
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getIngress().setActive(true);
		config.getApplication().setNamePrefix("test1-");
		config.getApplication().setOpenshift(true);
		config.getContent().setNamespaces(List.of(
			"${config.application.namePrefix}example-apps-staging",
			"${config.application.namePrefix}example-apps-production"
		));

		List<String> namespaceList = new ArrayList<>(Arrays.asList(
			"test1-argocd",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-" + config.getFeatures().getIngress().getIngressNamespace(),
			"test1-monitoring",
			"test1-registry",
			"test1-jenkins"
		));

		Application application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application.class);

		application.setNamespaceListToConfig(buildContext());

		assertThat(config.getApplication().getNamespaces().getActiveNamespaces())
			.containsExactlyInAnyOrderElementsOf(namespaceList);
	}

	@Test
	void handlesContentNamespacesWithoutTemplate() {
		config.getContent().setNamespaces(List.of(
			"example-apps-staging",
			"example-apps-production"
		));

		Application application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application.class);

		application.setNamespaceListToConfig(buildContext());

		assertThat(config.getApplication().getNamespaces().getActiveNamespaces()).containsAll(List.of(
			"example-apps-staging",
			"example-apps-production"
		));
	}

	@Test
	void handlesEmptyContentNamespaces() {
		Application application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application.class);

		application.setNamespaceListToConfig(buildContext());

		// No exception == happy
	}

	@Test
	void getActiveNamespacesCorrectlyInOpenshiftIfJenkinsAndScmAreExternal() {
		config.getRegistry().setActive(true);
		config.getJenkins().setActive(true);
		config.getJenkins().setInternal(false);
		config.getScm().setScmManager(new ScmTenantSchema.ScmManagerTenantConfig());
		config.getScm().getScmManager().setInternal(false);
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getIngress().setActive(true);
		config.getApplication().setNamePrefix("test1-");
		config.getApplication().setOpenshift(true);
		config.getContent().setNamespaces(List.of(
			"${config.application.namePrefix}example-apps-staging",
			"${config.application.namePrefix}example-apps-production"
		));

		List<String> namespaceList = new ArrayList<>(Arrays.asList(
			"test1-argocd",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-" + config.getFeatures().getIngress().getIngressNamespace(),
			"test1-monitoring",
			"test1-registry"
		));

		Application application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application.class);

		application.setNamespaceListToConfig(buildContext());

		assertThat(config.getApplication().getNamespaces().getActiveNamespaces())
			.containsExactlyInAnyOrderElementsOf(namespaceList);
	}

	private DeploymentContext buildContext() {
		return new ContextBuilder(config).build();
	}
}
