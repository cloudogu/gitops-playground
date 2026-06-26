package com.cloudogu.gitops.infrastructure.kubernetes.rbac

import com.cloudogu.gitops.config.Config

import groovy.util.logging.Slf4j

import io.fabric8.kubernetes.client.utils.Serialization

@Slf4j
class Role {
	String name
	String namespace
	Variant variant
	Config config

	Role(String name, String namespace, Variant variant, Config config) {
		if (!name?.trim()) throw new IllegalArgumentException("Role name must not be blank")
		if (!namespace?.trim()) throw new IllegalArgumentException("Role namespace must not be blank")
		if (!variant) throw new IllegalArgumentException("Role variant must not be null")
		if (!config) throw new IllegalArgumentException("Config must not be null")

		this.name = name
		this.namespace = namespace
		this.variant = variant
		this.config = config

		log.trace("Role object created with name='${name}' namespace='${namespace}' variant='${variant}' config=next rows!")
		log.trace(Serialization.asYaml(config.toYaml(false)))
	}

	enum Variant {
		ARGOCD("templates/kubernetes/rbac/argocd-role.ftl.yaml"),
		CLUSTER_ADMIN("")

		final String templatePath

		Variant(String templatePath) {
			this.templatePath = templatePath
		}
	}

	Map<String, Object> toTemplateParams() {
		return [name     : name,
		        namespace: namespace,
		        config   : config]
	}

	File getTemplateFile() {
		if (variant == Variant.CLUSTER_ADMIN) {
			throw new IllegalStateException("cluster-admin role shall not be created")
		}
		Variant.ARGOCD.templatePath
		log.trace("Role templatefile='${Variant.ARGOCD.templatePath}' returned with='${name}' namespace='${namespace}' variant='${variant}' config=next rows!")
		log.trace(Serialization.asYaml(config.toYaml(false)))
		return new File(variant.getTemplatePath())
	}

	File getOutputFile(File outputDir) {
		if (variant == Variant.CLUSTER_ADMIN) {
			throw new IllegalStateException("cluster-admin role shall not be created")
		}
		String filename = "role-${name}-${namespace}.yaml"
		return new File(outputDir, filename)
	}
}