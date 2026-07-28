package com.cloudogu.gitops.infrastructure.kubernetes.rbac;

import com.cloudogu.gitops.config.Config;
import java.io.File;
import java.util.Map;

public record Role(String name, String namespace, Variant variant, Config config) {

public Role {
	if (name == null || name.trim().isEmpty()) {
	throw new IllegalArgumentException("Role name must not be blank");
	}
	if (namespace == null || namespace.trim().isEmpty()) {
	throw new IllegalArgumentException("Role namespace must not be blank");
	}
	if (variant == null) {
	throw new IllegalArgumentException("Role variant must not be null");
	}
	if (config == null) {
	throw new IllegalArgumentException("Config must not be null");
	}
}

public enum Variant {
	ARGOCD("templates/kubernetes/rbac/argocd-role.ftl.yaml"),
	CLUSTER_ADMIN("");

	private final String templatePath;

	Variant(String templatePath) {
	this.templatePath = templatePath;
	}

	public String getTemplatePath() {
	return templatePath;
	}
}

public Map<String, Object> toTemplateParams() {
	return Map.of(
		"name", name,
		"namespace", namespace,
		"config", config);
}

public File getTemplateFile() {
	if (variant == Variant.CLUSTER_ADMIN) {
	throw new IllegalStateException("cluster-admin role shall not be created");
	}
	return new File(variant.getTemplatePath());
}

public File getOutputFile(File outputDir) {
	if (variant == Variant.CLUSTER_ADMIN) {
	throw new IllegalStateException("cluster-admin role shall not be created");
	}
	String filename = "role-" + name + "-" + namespace + ".yaml";
	return new File(outputDir, filename);
}
}
