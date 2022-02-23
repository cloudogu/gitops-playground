package com.cloudogu.gop.tools.k8s

import com.cloudogu.gop.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException

@Slf4j
class K8sClient {

    private KubernetesClient client

    K8sClient() {
        client = new DefaultKubernetesClient(new ConfigBuilder().withTrustCerts(true).build())
    }

    String getInternalNodeIp() {
        String foundNodeIp = "0.0.0.0"
        println client.nodes().list().getItems().toString()
        client.nodes().list().getItems().get(0).getStatus().getAddresses().forEach(address -> {
            if (address.getType() == "InternalIP") {
                foundNodeIp = address.getAddress()
            }
        })
        return foundNodeIp
    }

    void applyYaml(String yamlLocation, boolean useYamlMetadataNamespace = true, String namespace = "default") {
        if (yamlLocation.contains(".yaml")) {
            applySingleYaml(yamlLocation, useYamlMetadataNamespace, namespace)
        } else {
            applyYamlDirectory(yamlLocation, useYamlMetadataNamespace, namespace)
        }
    }

    private void applyYamlDirectory(String yamlDirectory, boolean useYamlMetadataNamespace, String namespace) {
        FileSystemUtils.getAllFilesFromDirectoryWithEnding(yamlDirectory, ".yaml").forEach(yaml -> {
            println yaml.getAbsolutePath()
            applySingleYaml(yaml.absolutePath, useYamlMetadataNamespace, namespace)
        })
    }

    private void applySingleYaml(String yamlLocation, boolean useYamlMetadataNamespace, String namespace) {
        List<HasMetadata> result = client.load(new FileInputStream(yamlLocation)).get()
        result.forEach(resource -> {
            def resourceNamespace = resource.getMetadata().getNamespace()
            try {
                if (resourceNamespace != null && !resourceNamespace.isEmpty() && useYamlMetadataNamespace) {
                    println "deploying via metadata"
                    client.resource(resource).inNamespace(resourceNamespace).createOrReplace()
                } else {
                    println "deploying via parameter"
                    client.resource(resource).inNamespace(namespace).createOrReplace()
                }
            } catch (KubernetesClientException e) {
                log.error("There was an error when communicating with the kubernetes API: ", e)
            }
        })
    }
}
