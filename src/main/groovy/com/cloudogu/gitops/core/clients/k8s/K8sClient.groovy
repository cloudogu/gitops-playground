package com.cloudogu.gitops.core.clients.k8s

import com.cloudogu.gitops.core.utils.CommandExecutor
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
        foundNodeIp = commandExecutor.execute(command)
        return foundNodeIp
    }

    private String waitForNode() {
        return commandExecutor.execute("kubectl get node -oname", "head -n1")
    }

    void applyYaml(String yamlLocation) {
        commandExecutor.execute("kubectl apply -f $yamlLocation")
    }
}
