package com.cloudogu.gitops.infrastructure.kubernetes.api;

import com.cloudogu.gitops.utils.MapUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;

@Slf4j
class K8sClientHelper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @SuppressWarnings("unchecked")
    static Pod applyPodOverrides(Pod pod, Map<String, ?> overrides) {
        Map<String, Object> podAsMap = Serialization.unmarshal(Serialization.asJson(pod), MAP_TYPE);
        Map<String, Object> normalizedOverrides = (Map<String, Object>) normalizeOverrideValue(overrides);
        Map<String, Object> mergedPod = MapUtils.deepMerge(normalizedOverrides, podAsMap);
        return Serialization.unmarshal(Serialization.asJson(mergedPod), Pod.class);
    }

    static Object normalizeOverrideValue(Object value) {
        if (value instanceof CharSequence) {
            return value.toString();
        }

        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((k, v) -> {
                result.put(k.toString(), normalizeOverrideValue(v));
            });
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

    static String collectPodRunOutput(KubernetesClient client, String podName, String namespace, boolean removePod, int defaultRetries, int sleepTime, K8sClient k8sClient) {
        String phase = null;
        try {
            phase = waitForPodCompletion(client, podName, namespace, defaultRetries, sleepTime);
            String logOutput = client.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .getLog();
            if (logOutput == null) {
                logOutput = "";
            }

            if ("Failed".equals(phase)) {
                throw new RuntimeException("Pod " + podName + " failed:\n" + logOutput);
            }

            return logOutput;
        } finally {
            if (removePod) {
                k8sClient.delete("pod", namespace, podName);
            }
        }
    }

    static String waitForPodCompletion(KubernetesClient client, String podName, String namespace, int defaultRetries, int sleepTime) {
        int tryCount = 0;

        while (tryCount < defaultRetries) {
            Pod pod = client.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .get();

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

        throw new RuntimeException("Failed to retrieve completed pod/" + podName + " after " + defaultRetries + " retries");
    }

    static PatchContext createPatchContext(String type) {
        PatchType patchType;

        if (type == null || type.isEmpty()) {
            patchType = PatchType.JSON_MERGE;
        } else {
            switch (type.toLowerCase()) {
                case "merge":
                case "json-merge":
                    patchType = PatchType.JSON_MERGE;
                    break;
                case "strategic":
                    patchType = PatchType.STRATEGIC_MERGE;
                    break;
                case "json":
                    patchType = PatchType.JSON;
                    break;
                default:
                    patchType = PatchType.STRATEGIC_MERGE;
            }
        }

        return new PatchContext.Builder().withPatchType(patchType).build();
    }

    static void validateNamespaceName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Namespace name must be provided and cannot be null or empty.");
        }
    }

    static void validateServiceNodePortPatch(String serviceName, String namespace, String portName, int newNodePort) {
        if (serviceName == null || serviceName.isEmpty() || namespace == null || namespace.isEmpty() || portName == null || portName.isEmpty() || newNodePort <= 0) {
            throw new IllegalArgumentException("Service name, namespace, port name, and valid nodePort must be provided");
        }
    }

    static void validateWaitForResourcePhaseParams(String resourceType, String resourceName, String namespace,
                                                   String desiredPhase, int timeoutSeconds, int checkIntervalSeconds) {
        if (resourceType == null || resourceType.isEmpty() || resourceName == null || resourceName.isEmpty() || namespace == null || namespace.isEmpty() || desiredPhase == null || desiredPhase.isEmpty()) {
            throw new IllegalArgumentException("Resource type, name, namespace, and desired phase must be provided");
        }
        if (timeoutSeconds <= 0 || checkIntervalSeconds <= 0) {
            throw new IllegalArgumentException("Timeout and check interval must be greater than zero");
        }
    }

    static io.fabric8.kubernetes.client.dsl.Resource getResourceClient(KubernetesClient client, String resourceType, String name, String resolvedNamespace) {
        switch (resourceType.toLowerCase()) {
            case "pod":
            case "pods":
                return client.pods().inNamespace(resolvedNamespace).withName(name);

            case "service":
            case "services":
            case "svc":
                return client.services().inNamespace(resolvedNamespace).withName(name);

            case "deployment":
            case "deployments":
                return client.apps().deployments().inNamespace(resolvedNamespace).withName(name);

            case "configmap":
            case "configmaps":
            case "cm":
                return client.configMaps().inNamespace(resolvedNamespace).withName(name);

            case "secret":
            case "secrets":
                return client.secrets().inNamespace(resolvedNamespace).withName(name);

            case "namespace":
            case "namespaces":
            case "ns":
                return client.namespaces().withName(name);

            case "node":
            case "nodes":
                return client.nodes().withName(name);

            case "serviceaccount":
            case "serviceaccounts":
                return client.serviceAccounts().inNamespace(resolvedNamespace).withName(name);

            default:
                log.debug("Searching API resource via discovery for resourceType={}, name={}, ns={}", resourceType, name, resolvedNamespace);
                return getCustomResourceClient(client, resourceType, name, resolvedNamespace);
        }
    }

    static io.fabric8.kubernetes.client.dsl.Resource getCustomResourceClient(KubernetesClient client, String resourceType, String name, String namespace) {
        String normalized = resourceType.toLowerCase();

        Map<String, Object> match = findApiResourceViaDiscovery(client, normalized, resourceType);

        if (match == null) {
            throw new K8sClient.KubernetesApiResourceNotFoundException(resourceType);
        }

        log.debug("Resolved '{}' via discovery to {}/{} kind={} plural={} namespaced={}",
                resourceType, match.get("group"), match.get("version"), match.get("kind"), match.get("plural"), match.get("namespaced"));

        ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                .withGroup((String) match.get("group"))
                .withVersion((String) match.get("version"))
                .withKind((String) match.get("kind"))
                .withPlural((String) match.get("plural"))
                .withNamespaced((Boolean) match.get("namespaced"))
                .build();

        var resourceClient = client.genericKubernetesResources(context);
        return (Boolean) match.get("namespaced") ? resourceClient.inNamespace(namespace).withName(name) : resourceClient.withName(name);
    }

    static Map<String, Object> findApiResourceViaDiscovery(KubernetesClient client, String normalized, String original) {
        List<io.fabric8.kubernetes.api.model.APIGroup> apiGroups;
        try {
            var groupList = client.getApiGroups();
            apiGroups = groupList != null ? groupList.getGroups() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to discover API groups: {}", e.getMessage());
            return null;
        }

        for (var group : apiGroups) {
            List<String> versions = new ArrayList<>();
            if (group.getPreferredVersion() != null && group.getPreferredVersion().getVersion() != null) {
                versions.add(group.getPreferredVersion().getVersion());
            }
            if (group.getVersions() != null) {
                for (var v : group.getVersions()) {
                    if (v.getVersion() != null && !versions.contains(v.getVersion())) {
                        versions.add(v.getVersion());
                    }
                }
            }

            for (String version : versions) {
                List<io.fabric8.kubernetes.api.model.APIResource> resources;
                try {
                    var resourceList = client.getApiResources(group.getName() + "/" + version);
                    resources = resourceList != null ? resourceList.getResources() : Collections.emptyList();
                } catch (Exception e) {
                    log.trace("Failed to fetch {}/{}: {}", group.getName(), version, e.getMessage());
                    continue;
                }

                io.fabric8.kubernetes.api.model.APIResource resolvedResult = null;
                for (var res : resources) {
                    if (res.getName() != null && !res.getName().contains("/")) {
                        boolean match = res.getKind().equalsIgnoreCase(original) || res.getName().equalsIgnoreCase(normalized) ||
                                (res.getSingularName() != null && res.getSingularName().equalsIgnoreCase(normalized));
                        if (!match && res.getShortNames() != null) {
                            for (String shortName : res.getShortNames()) {
                                if (shortName.equalsIgnoreCase(normalized)) {
                                    match = true;
                                    break;
                                }
                            }
                        }
                        if (match) {
                            resolvedResult = res;
                            break;
                        }
                    }
                }

                if (resolvedResult != null) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("group", group.getName());
                    map.put("version", version);
                    map.put("kind", resolvedResult.getKind());
                    map.put("plural", resolvedResult.getName());
                    map.put("namespaced", resolvedResult.getNamespaced());
                    return map;
                }
            }
        }
        return null;
    }

    static void deleteResourcesByType(KubernetesClient client, String resource, String namespace, Map<String, String> labels) {
        switch (resource.toLowerCase()) {
            case "secret":
            case "secrets":
                client.secrets().inNamespace(namespace).withLabels(labels).delete();
                break;

            case "pod":
            case "pods":
                client.pods().inNamespace(namespace).withLabels(labels).delete();
                break;

            case "service":
            case "services":
            case "svc":
                client.services().inNamespace(namespace).withLabels(labels).delete();
                break;

            case "deployment":
            case "deployments":
                client.apps().deployments().inNamespace(namespace).withLabels(labels).delete();
                break;

            case "configmap":
            case "configmaps":
            case "cm":
                client.configMaps().inNamespace(namespace).withLabels(labels).delete();
                break;

            default:
                Map<String, Object> match = findApiResourceViaDiscovery(client, resource.toLowerCase(), resource);
                if (match != null) {
                    ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                            .withGroup((String) match.get("group"))
                            .withVersion((String) match.get("version"))
                            .withKind((String) match.get("kind"))
                            .withPlural((String) match.get("plural"))
                            .withNamespaced((Boolean) match.get("namespaced"))
                            .build();
                    client.genericKubernetesResources(context).inNamespace(namespace).withLabels(labels).delete();
                } else {
                    log.warn("Failed to find resource definition for deletion of {}", resource);
                }
        }
    }
}
