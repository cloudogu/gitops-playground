package com.cloudogu.gitops.utils


import groovy.util.logging.Slf4j

@Slf4j
class K8sClient {

    private CommandExecutor commandExecutor

    K8sClient(CommandExecutor commandExecutor = new CommandExecutor()) {
        this.commandExecutor = commandExecutor
    }

    String getInternalNodeIp() {
        String foundNodeIp = "0.0.0.0"
        String node = waitForNode()
        String[] command = ["kubectl", "get", "$node", "--template='{{range .status.addresses}}{{ if eq .type \"InternalIP\" }}{{.address}}{{end}}{{end}}'"]
        foundNodeIp = commandExecutor.execute(command).stdOut
        return foundNodeIp
    }

    private String waitForNode() {
        return commandExecutor.execute("kubectl get node -oname", "head -n1").stdOut
    }

    String applyYaml(String yamlLocation) {
        commandExecutor.execute("kubectl apply -f $yamlLocation").stdOut
    }

    void createSecret(String type, String name, String namespace = '', Tuple2... literals) {
        if (!literals) {
            throw new RuntimeException("Missing literals")
        }
        String command =
                "kubectl create secret ${type} ${name}${namespace ? " -n ${namespace}" : ''} " +
                        literals.collect { "--from-literal=${it.v1}=${it.v2}"}.join(" ")
        commandExecutor.execute(command).stdOut
    }
}
