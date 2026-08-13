package com.cloudogu.gitops.infrastructure.kubernetes.rbac;

import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
public class ServiceAccountRef {
	private final String name;
	private final String namespace;

	public ServiceAccountRef(String name, String namespace) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("ServiceAccount name must not be blank");
		}
		if (namespace == null || namespace.trim().isEmpty()) {
			throw new IllegalArgumentException("ServiceAccount namespace must not be blank");
		}
		this.name = name;
		this.namespace = namespace;
	}

	public static List<ServiceAccountRef> fromNames(String namespace, List<String> names) {
		if (namespace == null || namespace.trim().isEmpty()) {
			throw new IllegalArgumentException("Namespace must not be blank for service accounts");
		}
		if (names == null) {
			return List.of();
		}

		return names.stream()
		            .filter(name -> name != null && !name.trim().isEmpty())
		            .distinct()
		            .map(name -> new ServiceAccountRef(name, namespace))
		            .toList();
	}

	public Map<String, String> toMap() {
		return Map.of("name", name, "namespace", namespace);
	}
}
