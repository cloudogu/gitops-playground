package com.cloudogu.gitops.application;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.DeploymentOrchestrator;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryProvisioning;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractTool;
import com.cloudogu.gitops.utils.TemplatingEngine;
import com.cloudogu.gitops.utils.Tuple;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Singleton
@Slf4j
public class Application {

	private static final String DEFAULT_GOP_NAMESPACE = "gop-job";

	@Getter
	private final List<AbstractTool> tools;
	private final ContextBuilder contextBuilder;
	private final K8sClient k8sClient;
	private final GitHandler gitHandler;
	private final RepositoryProvisioning repositoryProvisioning;
	private final DeploymentOrchestrator deploymentOrchestrator;

	public Application(
		ContextBuilder contextBuilder,
		K8sClient k8sClient,
		GitHandler gitHandler,
		RepositoryProvisioning repositoryProvisioning,
		DeploymentOrchestrator deploymentOrchestrator) {
		this.contextBuilder = contextBuilder;
		this.k8sClient = k8sClient;
		this.gitHandler = gitHandler;
		this.repositoryProvisioning = repositoryProvisioning;
		this.deploymentOrchestrator = deploymentOrchestrator;
		this.tools = deploymentOrchestrator.getTools();
	}

	public void start() {
		log.debug("Starting Application");

		DeploymentContext context = contextBuilder.build();

		setNamespaceListToConfig(context);
		storeGopInformationInSecret(context);

		gitHandler.validate();
		gitHandler.prepareProviders(context);
		repositoryProvisioning.prepare(context);
		try (RepositoryWorkspace workspace = repositoryProvisioning.provideWorkspace(context)) {
			deploymentOrchestrator.deployTools(context, workspace);
		}

		log.debug("Application finished");
	}

	private void storeGopInformationInSecret(DeploymentContext context) {
		String namespace = DEFAULT_GOP_NAMESPACE;
		if (context.getConfig().getApplication().getGopNamespace() != null && !context.getConfig()
		                                                                              .getApplication()
		                                                                              .getGopNamespace()
		                                                                              .isEmpty()) {
			namespace = context.getConfig().getApplication().getNamePrefix() + context.getConfig()
			                                                                          .getApplication()
			                                                                          .getGopNamespace();
		} else if (this.k8sClient.getCurrentNamespace() != null) {
			namespace = this.k8sClient.getCurrentNamespace();
		} else {
			// keep default namespace
		}
		log.debug("Storing GOP configuration in secret 'gop-configuration' in namespace '{}'", namespace);
		k8sClient.createNamespace(namespace);
		k8sClient.createSecret(
			"generic",
			"gop-configuration",
			namespace,
			new Tuple<>(
				"gop-initial-password", context.getConfig()
				                               .getApplication()
				                               .getPassword()
			),
			new Tuple<>(
				"gop-config", context.getConfig()
				                     .toYaml(true)
			)
		);
	}

	public void setNamespaceListToConfig(DeploymentContext context) {
		LinkedHashSet<String> tenantNamespaces = new LinkedHashSet<>();
		TemplatingEngine engine = new TemplatingEngine();

		if (context.getConfig().getContent() != null && context.getConfig().getContent().getNamespaces() != null) {
			for (String ns : context.getConfig().getContent().getNamespaces()) {
				try {
					tenantNamespaces.add(engine.template(
						ns, Map.of(
							"config",
							context.getConfig(),
							"statics",
							new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build()
							                                                             .getStaticModels()
						)
					));
				} catch (Exception e) {
					throw new RuntimeException("Failed to render namespace template: " + ns, e);
				}
			}
			context.getConfig().getContent().setNamespaces(new ArrayList<>(tenantNamespaces));
		}

		LinkedHashSet<String> dedicatedNamespaces = new LinkedHashSet<>();
		for (AbstractTool tool : this.tools) {
			String activeNs = tool.getActiveNamespaceFromFeature(context);
			if (activeNs != null && !activeNs.isEmpty()) {
				dedicatedNamespaces.add(activeNs);
			}
		}

		context.getConfig().getApplication().getNamespaces().setDedicatedNamespaces(dedicatedNamespaces);
		context.getConfig().getApplication().getNamespaces().setTenantNamespaces(tenantNamespaces);
		log.debug(
			"Active namespaces retrieved: {}", context.getConfig()
			                                          .getApplication()
			                                          .getNamespaces()
			                                          .getActiveNamespaces()
		);
	}
}
