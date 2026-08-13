package com.cloudogu.gitops.infrastructure.kubernetes.api;

import com.cloudogu.gitops.utils.MapUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.api.model.APIGroupList;
import io.fabric8.kubernetes.api.model.APIResource;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.api.model.GroupVersionForDiscovery;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.micronaut.core.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
class K8sClientHelper {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String GROUP_KEY = "group";
	private static final String VERSION_KEY = "version";
	private static final String KIND_KEY = "kind";
	private static final String PLURAL_KEY = "plural";
	private static final String NAMESPACED_KEY = "namespaced";

	private K8sClientHelper() {
	}

	static Pod applyPodOverrides(Pod pod, Map<String, ?> overrides) {
		Map<String, Object> podAsMap = Serialization.unmarshal(Serialization.asJson(pod), MAP_TYPE);
		Map<String, Object> normalizedOverrides = MapUtils.asStringObjectMap(normalizeOverrideValue(overrides));
		Map<String, Object> mergedPod = MapUtils.deepMerge(normalizedOverrides, podAsMap);
		return Serialization.unmarshal(Serialization.asJson(mergedPod), Pod.class);
	}

	static Object normalizeOverrideValue(Object value) {
		if (value instanceof CharSequence) {
			return value.toString();
		}

		if (value instanceof Map) {
			Map<String, Object> result = new LinkedHashMap<>();
			((Map<?, ?>) value).forEach((k, v) -> result.put(k.toString(), normalizeOverrideValue(v)));
			return result;
		}

		if (value instanceof Collection) {
			List<Object> result = new ArrayList<>();
			for (Object entry : (Collection<?>) value) {
				result.add(normalizeOverrideValue(entry));
			}
			return result;
		}

		return value;
	}

	static void applyRunParams(Pod pod, List<String> params) {
		String restartPolicy = null;
		for (String param : params) {
			if (param.startsWith("--restart=")) {
				restartPolicy = param.substring("--restart=".length());
				break;
			}
		}
		if (restartPolicy != null) {
			pod.getSpec().setRestartPolicy(restartPolicy);
		}
	}

	static boolean shouldReturnPodOutput(List<String> params) {
		return params.contains("--rm") || params.contains("-i") || params.contains("-it") || params.contains("-ti");
	}

	static boolean shouldRemovePod(List<String> params) {
		return params.contains("--rm");
	}

	static String collectPodRunOutput(
		KubernetesClient client,
		String podName,
		String namespace,
		boolean removePod,
		int defaultRetries,
		int sleepTime,
		K8sClient k8sClient) {
		String phase;
		try {
			phase = waitForPodCompletion(client, podName, namespace, defaultRetries, sleepTime);
			String logOutput = client.pods().inNamespace(namespace).withName(podName).getLog();
			if (logOutput == null) {
				logOutput = "";
			}

			if ("Failed".equals(phase)) {
				throw new IllegalStateException("Pod " + podName + " failed:\n" + logOutput);
			}

			return logOutput;
		} finally {
			if (removePod) {
				k8sClient.delete("pod", namespace, podName);
			}
		}
	}

	static String waitForPodCompletion(
		KubernetesClient client,
		String podName,
		String namespace,
		int defaultRetries,
		int sleepTime) {
		int tryCount = 0;

		while (tryCount < defaultRetries) {
			Pod pod = client.pods().inNamespace(namespace).withName(podName).get();

			String phase = (pod != null && pod.getStatus() != null) ? pod.getStatus().getPhase() : null;
			if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
				return phase;
			}

			tryCount++;
			log.debug("Still waiting for pod/{} to complete... (try {}/{})", podName, tryCount, defaultRetries);
			try {
				Thread.sleep(sleepTime);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted while waiting for pod completion", e);
			}
		}

		throw new IllegalStateException("Failed to retrieve completed pod/" + podName + " after " + defaultRetries + " retries");
	}

	static PatchContext createPatchContext(String type) {
		PatchType patchType = type == null || type.isEmpty() ? PatchType.JSON_MERGE : switch (type.toLowerCase(
			Locale.ROOT)) {
			case "merge", "json-merge" -> PatchType.JSON_MERGE;
			case "strategic" -> PatchType.STRATEGIC_MERGE;
			case "json" -> PatchType.JSON;
			default -> throw new IllegalArgumentException("Unsupported patch type: " + type);
		};

		return new PatchContext.Builder().withPatchType(patchType).build();
	}

	static void validateNamespaceName(String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Namespace name must be provided and cannot be null or empty.");
		}
	}

	static void validateServiceNodePortPatch(String serviceName, String namespace, String portName, int newNodePort) {
		if (StringUtils.isEmpty(serviceName) || StringUtils.isEmpty(namespace) || StringUtils.isEmpty(portName) || newNodePort <= 0) {
			throw new IllegalArgumentException("Service name, namespace, port name, and valid nodePort must be provided");
		}
	}

	static void validateWaitForResourcePhaseParams(
		String resourceType,
		String resourceName,
		String namespace,
		String desiredPhase,
		int timeoutSeconds,
		int checkIntervalSeconds) {
		if (StringUtils.isEmpty(resourceType) || StringUtils.isEmpty(resourceName) || StringUtils.isEmpty(namespace) || StringUtils.isEmpty(
			desiredPhase)) {
			throw new IllegalArgumentException("Resource type, name, namespace, and desired phase must be provided");
		}
		if (timeoutSeconds <= 0 || checkIntervalSeconds <= 0) {
			throw new IllegalArgumentException("Timeout and check interval must be greater than zero");
		}
	}

	@SuppressWarnings("unchecked")
	static io.fabric8.kubernetes.client.dsl.Resource<HasMetadata> getResourceClient(
		KubernetesClient client,
		String resourceType,
		String name,
		String resolvedNamespace) {
		return (io.fabric8.kubernetes.client.dsl.Resource<HasMetadata>) resolveResourceClient(
			client,
			resourceType,
			name,
			resolvedNamespace
		);
	}

	private static io.fabric8.kubernetes.client.dsl.Resource<?> resolveResourceClient(
		KubernetesClient client,
		String resourceType,
		String name,
		String resolvedNamespace) {
		return switch (resourceType.toLowerCase(Locale.ROOT)) {
			case "pod", "pods" -> client.pods().inNamespace(resolvedNamespace).withName(name);
			case "service", "services", "svc" -> client.services().inNamespace(resolvedNamespace).withName(name);
			case "deployment", "deployments" ->
				client.apps().deployments().inNamespace(resolvedNamespace).withName(name);
			case "configmap", "configmaps", "cm" -> client.configMaps().inNamespace(resolvedNamespace).withName(name);
			case "secret", "secrets" -> client.secrets().inNamespace(resolvedNamespace).withName(name);
			case "namespace", "namespaces", "ns" -> client.namespaces().withName(name);
			case "node", "nodes" -> client.nodes().withName(name);
			case "serviceaccount", "serviceaccounts" ->
				client.serviceAccounts().inNamespace(resolvedNamespace).withName(name);
			default -> {
				log.debug(
					"Searching API resource via discovery for resourceType={}, name={}, ns={}",
					resourceType,
					name,
					resolvedNamespace
				);
				yield getCustomResourceClient(client, resourceType, name, resolvedNamespace);
			}
		};
	}

	static io.fabric8.kubernetes.client.dsl.Resource<?> getCustomResourceClient(
		KubernetesClient client,
		String resourceType,
		String name,
		String namespace) {
		String normalized = resourceType.toLowerCase(Locale.ROOT);

		Map<String, Object> match = findApiResourceViaDiscovery(client, normalized, resourceType);

		if (match.isEmpty()) {
			throw new K8sClient.KubernetesApiResourceNotFoundException(resourceType);
		}

		log.debug(
			"Resolved '{}' via discovery to {}/{} kind={} plural={} namespaced={}",
			resourceType,
			match.get(GROUP_KEY),
			match.get(VERSION_KEY),
			match.get(KIND_KEY),
			match.get(PLURAL_KEY),
			match.get(NAMESPACED_KEY)
		);

		ResourceDefinitionContext context = toResourceDefinitionContext(match);
		boolean namespaced = Boolean.TRUE.equals(match.get(NAMESPACED_KEY));

		// type is MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
		// Resource<GenericKubernetesResource>>; kept as `var` deliberately.
		var resourceClient = client.genericKubernetesResources(context);
		return namespaced ? resourceClient.inNamespace(namespace).withName(name) : resourceClient.withName(name);
	}

	private static ResourceDefinitionContext toResourceDefinitionContext(Map<String, Object> match) {
		return new ResourceDefinitionContext.Builder().withGroup((String) match.get(GROUP_KEY))
		                                              .withVersion((String) match.get(VERSION_KEY))
		                                              .withKind((String) match.get(KIND_KEY))
		                                              .withPlural((String) match.get(PLURAL_KEY))
		                                              .withNamespaced(Boolean.TRUE.equals(match.get(NAMESPACED_KEY)))
		                                              .build();
	}

	static Map<String, Object> findApiResourceViaDiscovery(
		KubernetesClient client,
		String normalized,
		String original) {
		for (APIGroup group : fetchApiGroups(client)) {
			Map<String, Object> match = findApiResourceInGroup(client, group, normalized, original);
			if (!match.isEmpty()) {
				return match;
			}
		}
		return Collections.emptyMap();
	}

	private static List<APIGroup> fetchApiGroups(KubernetesClient client) {
		try {
			APIGroupList groupList = client.getApiGroups();
			return groupList != null ? groupList.getGroups() : Collections.emptyList();
		} catch (Exception e) {
			log.warn("Failed to discover API groups: {}", e.getMessage());
			return Collections.emptyList();
		}
	}

	private static Map<String, Object> findApiResourceInGroup(
		KubernetesClient client,
		APIGroup group,
		String normalized,
		String original) {
		for (String version : groupVersions(group)) {
			APIResource resolved = findMatchingResourceInVersion(client, group, version, normalized, original);
			if (resolved != null) {
				return toResourceMatch(group, version, resolved);
			}
		}
		return Collections.emptyMap();
	}

	private static List<String> groupVersions(APIGroup group) {
		List<String> versions = new ArrayList<>();
		if (group.getPreferredVersion() != null && group.getPreferredVersion().getVersion() != null) {
			versions.add(group.getPreferredVersion().getVersion());
		}
		if (group.getVersions() != null) {
			for (GroupVersionForDiscovery v : group.getVersions()) {
				if (v.getVersion() != null && !versions.contains(v.getVersion())) {
					versions.add(v.getVersion());
				}
			}
		}
		return versions;
	}

	private static APIResource findMatchingResourceInVersion(
		KubernetesClient client,
		APIGroup group,
		String version,
		String normalized,
		String original) {
		for (APIResource res : fetchApiResources(client, group, version)) {
			if (isTopLevelResource(res) && matchesResource(res, normalized, original)) {
				return res;
			}
		}
		return null;
	}

	private static List<APIResource> fetchApiResources(KubernetesClient client, APIGroup group, String version) {
		try {
			APIResourceList resourceList = client.getApiResources(group.getName() + "/" + version);
			return resourceList != null ? resourceList.getResources() : Collections.emptyList();
		} catch (Exception e) {
			log.trace("Failed to fetch {}/{}: {}", group.getName(), version, e.getMessage());
			return Collections.emptyList();
		}
	}

	private static boolean isTopLevelResource(APIResource res) {
		return res.getName() != null && !res.getName().contains("/");
	}

	private static boolean matchesResource(APIResource res, String normalized, String original) {
		boolean match = res.getKind().equalsIgnoreCase(original) || res.getName()
		                                                               .equalsIgnoreCase(normalized) || (res.getSingularName() != null && res.getSingularName()
		                                                                                                                                     .equalsIgnoreCase(
																																				 normalized));
		if (match || res.getShortNames() == null) {
			return match;
		}
		for (String shortName : res.getShortNames()) {
			if (shortName.equalsIgnoreCase(normalized)) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> toResourceMatch(APIGroup group, String version, APIResource resolved) {
		Map<String, Object> map = new HashMap<>();
		map.put(GROUP_KEY, group.getName());
		map.put(VERSION_KEY, version);
		map.put(KIND_KEY, resolved.getKind());
		map.put(PLURAL_KEY, resolved.getName());
		map.put(NAMESPACED_KEY, resolved.getNamespaced());
		return map;
	}

	static void deleteResourcesByType(
		KubernetesClient client,
		String resource,
		String namespace,
		Map<String, String> labels) {
		switch (resource.toLowerCase(Locale.ROOT)) {
			case "secret", "secrets":
				client.secrets().inNamespace(namespace).withLabels(labels).delete();
				break;

			case "pod", "pods":
				client.pods().inNamespace(namespace).withLabels(labels).delete();
				break;

			case "service", "services", "svc":
				client.services().inNamespace(namespace).withLabels(labels).delete();
				break;

			case "deployment", "deployments":
				client.apps().deployments().inNamespace(namespace).withLabels(labels).delete();
				break;

			case "configmap", "configmaps", "cm":
				client.configMaps().inNamespace(namespace).withLabels(labels).delete();
				break;

			default:
				Map<String, Object> match = findApiResourceViaDiscovery(
					client,
					resource.toLowerCase(Locale.ROOT),
					resource
				);
				if (!match.isEmpty()) {
					ResourceDefinitionContext context = toResourceDefinitionContext(match);
					client.genericKubernetesResources(context).inNamespace(namespace).withLabels(labels).delete();
				} else {
					log.warn("Failed to find resource definition for deletion of {}", resource);
				}
		}
	}
}
