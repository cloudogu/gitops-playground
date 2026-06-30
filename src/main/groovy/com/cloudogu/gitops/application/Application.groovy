package com.cloudogu.gitops.application

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.TemplatingEngine

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder

@Slf4j
@Singleton
class Application {

	final List<Tool> features
	final DeploymentContext context
	final K8sClient k8sClient
	final GitHandler gitHandler

	Application(DeploymentContext context, K8sClient k8sClient, GitHandler gitHandler,
		List<Tool> features) {
		this.context = context
		// Order is important. Enforced by @Order-Annotation on the Singletons
		this.gitHandler = gitHandler
		this.features = features
		this.k8sClient = k8sClient
	}

	def start() {
		log.debug("Starting Application")

		setNamespaceListToConfig(context)
		// if set, stores configuration in a secret.
		storeGopInformationInSecret(context)

		gitHandler.validate()
		gitHandler.prepareProviders()

		features.forEach(feature -> {
			feature.validate()
		})
		features.forEach(feature -> {
			feature.install()
		})
		log.debug("Application finished")
	}

	private void storeGopInformationInSecret(DeploymentContext context) {
		String namespace = "gop-job"
		// Fallback, if run from IDE
		if (!context.config.application.gopNamespace.isEmpty()) {
			// if set, take namespace from configuration
			namespace = "${context.config.application.namePrefix}${context.config.application.gopNamespace}"
		} else if (this.k8sClient.getCurrentNamespace() != null) {
			// if gop-namespace not set, take namespace from running GOP
			namespace = this.k8sClient.getCurrentNamespace()
		}
		log.debug("Storing GOP configuration in secret 'gop-configuration' in namespace '${namespace}'")
		k8sClient.createNamespace(namespace)
		k8sClient.createSecret('generic', 'gop-configuration', namespace,
			new Tuple2('gop-initial-password', context.config.application.password),
			new Tuple2('gop-config', context.config.toYaml(true)))
	}

	List<Tool> getFeatures() {
		return features
	}

	void setNamespaceListToConfig(DeploymentContext context) {
		LinkedHashSet<String> dedicatedNamespaces = new LinkedHashSet<>()
		LinkedHashSet<String> tenantNamespaces = new LinkedHashSet<>()
		def engine = new TemplatingEngine()

		context.config.content.namespaces.each { String ns ->
			tenantNamespaces.add(engine.template(ns, [config : context.config,
			                                          // Allow for using static classes inside the templates
			                                          statics: new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()]))
		}
		context.config.content.namespaces = tenantNamespaces.toList()

		//iterates over all FeatureWithImages and gets their namespaces
		dedicatedNamespaces.addAll(this.features
			.collect { it.activeNamespaceFromFeature }
			.findAll { it }
			.unique()
			.collect { "${it}".toString() })

		context.config.application.namespaces.dedicatedNamespaces = dedicatedNamespaces
		context.config.application.namespaces.tenantNamespaces = tenantNamespaces
		log.debug("Active namespaces retrieved: {}", context.config.application.namespaces.activeNamespaces)
	}

	void setNamespaceListToConfig() {
		setNamespaceListToConfig(context)
	}

}