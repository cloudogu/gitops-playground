package com.cloudogu.gop.application.clients.k8s

import com.cloudogu.gop.application.utils.CommandExecutor
import groovy.util.logging.Slf4j

@Slf4j
class K8sClient {

    static String getInternalNodeIp() {
        String foundNodeIp = "0.0.0.0"
        String node = waitForNode()
        String[] command = ["kubectl", "get", "$node", "--template='{{range .status.addresses}}{{ if eq .type \"InternalIP\" }}{{.address}}{{end}}{{end}}'"]
        foundNodeIp = CommandExecutor.execute(command)
        return foundNodeIp
    }

    private static String waitForNode() {
        return CommandExecutor.execute("kubectl get node -oname", "head -n1")
    }

    static void applyYaml(String yamlLocation) {
        CommandExecutor.execute("kubectl apply -f $yamlLocation")
    }

//    void applyYaml(String yamlLocation, boolean useYamlMetadataNamespace = true, String namespace = "default") {
//        if (yamlLocation.contains(".yaml")) {
//            applySingleYaml(yamlLocation, useYamlMetadataNamespace, namespace)
//        } else {
//            applyYamlDirectory(yamlLocation, useYamlMetadataNamespace, namespace)
//        }
//    }
//
//    private void applyYamlDirectory(String yamlDirectory, boolean useYamlMetadataNamespace, String namespace) {
//        FileSystemUtils.getAllFilesFromDirectoryWithEnding(yamlDirectory, ".yaml").forEach(yaml -> {
//            println yaml.getAbsolutePath()
//            applySingleYaml(yaml.absolutePath, useYamlMetadataNamespace, namespace)
//        })
//    }
//
//    private void applySingleYaml(String yamlLocation, boolean useYamlMetadataNamespace, String namespace) {
//        List<HasMetadata> result = client.load(new FileInputStream(yamlLocation)).get()
//        result.forEach(resource -> {
//            def resourceNamespace = resource.getMetadata().getNamespace()
//            try {
//                if (resourceNamespace != null && !resourceNamespace.isEmpty() && useYamlMetadataNamespace) {
//                    println "deploying via metadata"
//                    client.resource(resource).inNamespace(resourceNamespace).createOrReplace()
//                } else {
//                    println "deploying via parameter"
//                    client.resource(resource).inNamespace(namespace).createOrReplace()
//                }
//            } catch (KubernetesClientException e) {
//                log.error("There was an error when communicating with the kubernetes API: ", e)
//            }
//        })
//    }
}
