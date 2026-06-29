package com.cloudogu.gitops.infrastructure.kubernetes.rbac

import com.cloudogu.gitops.application.context.DeploymentContext

class Role {
	String name
	String namespace
	Variant variant
	DeploymentContext context

	Role(String name, String namespace, Variant variant, DeploymentContext context) {
		if (!name?.trim()) throw new IllegalArgumentException("Role name must not be blank")
		if (!namespace?.trim()) throw new IllegalArgumentException("Role namespace must not be blank")
		if (!variant) throw new IllegalArgumentException("Role variant must not be null")
		if (!context) throw new IllegalArgumentException("DeploymentContext must not be null")

		this.name = name
		this.namespace = namespace
		this.variant = variant
		this.context = context
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
		        config   : context.config]
	}

	File getTemplateFile() {
		if (variant == Variant.CLUSTER_ADMIN) {
			throw new IllegalStateException("cluster-admin role shall not be created")
		}
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