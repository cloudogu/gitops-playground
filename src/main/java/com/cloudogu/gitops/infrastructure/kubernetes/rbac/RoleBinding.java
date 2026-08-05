package com.cloudogu.gitops.infrastructure.kubernetes.rbac;

import lombok.Getter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
public class RoleBinding {
	private final String name;
	private final String kind;
	private final String namespace;
	private final String roleName;
	private final String roleKind;
	private final List<ServiceAccountRef> serviceAccounts;

	public RoleBinding(String name, String namespace, String roleName, List<ServiceAccountRef> serviceAccounts) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("RoleBinding name must not be blank");
		}
		if (namespace == null || namespace.trim().isEmpty()) {
			throw new IllegalArgumentException("RoleBinding namespace must not be blank");
		}
		if (roleName == null || roleName.trim().isEmpty()) {
			throw new IllegalArgumentException("Role name must not be blank");
		}
		if (serviceAccounts == null || serviceAccounts.isEmpty()) {
			throw new IllegalArgumentException("At least one service account is required");
		}

		this.name = name;
		this.namespace = namespace;
		this.roleName = roleName;
		this.serviceAccounts = new ArrayList<>(serviceAccounts);

		if ("cluster-admin".equals(roleName)) {
			this.kind = "ClusterRoleBinding";
			this.roleKind = "ClusterRole";
		} else {
			this.kind = "RoleBinding";
			this.roleKind = "Role";
		}
	}

	public Map<String, Object> toTemplateParams() {
		return Map.of(
			"name",
			name,
			"kind",
			kind,
			"namespace",
			namespace,
			"roleName",
			roleName,
			"roleKind",
			roleKind,
			"serviceAccounts",
			serviceAccounts.stream()
			               .map(ServiceAccountRef::toMap)
			               .toList()
		);
	}

	public String getTemplatePath() {
		return "templates/kubernetes/rbac/rolebinding.ftl.yaml";
	}

	public File getTemplateFile() {
		return new File(getTemplatePath());
	}

	public File getOutputFile(File outputDir) {
		String filename = "rolebinding-" + name + "-" + namespace + ".yaml";
		return new File(outputDir, filename);
	}
}
