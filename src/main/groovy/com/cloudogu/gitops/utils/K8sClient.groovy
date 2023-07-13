package com.cloudogu.gitops.utils


import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class K8sClient {

    private CommandExecutor commandExecutor

    K8sClient(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor
    }

    String getInternalNodeIp() {
        String foundNodeIp = "0.0.0.0"
        String node = waitForNode()

        // For k3d this is either the host's IP or the IP address of the k3d API server's container IP (when --bind-localhost=false)
        // Note that this might return multiple InternalIP (IPV4 and IPV6) - we assume the first one is IPV4 (break after first)
        String[] command = ["kubectl", "get", "$node", 
                            "--template='{{range .status.addresses}}{{ if eq .type \"InternalIP\" }}{{.address}}{{break}}{{end}}{{end}}'"]
        foundNodeIp = commandExecutor.execute(command).stdOut
        return foundNodeIp
    }

    private String waitForNode() {
        return commandExecutor.execute("kubectl get node -oname", "head -n1").stdOut
    }

    String applyYaml(String yamlLocation) {
        commandExecutor.execute("kubectl apply -f $yamlLocation").stdOut
    }

    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createSecret(String type, String name, String namespace = '', Tuple2... literals) {
        if (!literals) {
            throw new RuntimeException("Missing literals")
        }
        String command =
                "kubectl create secret ${type} ${name}${namespace ? " -n ${namespace}" : ''} " +
                        literals.collect { "--from-literal=${it.v1}=${it.v2}"}.join(' ') +
                        ' --dry-run=client -oyaml'
        commandExecutor.execute(command, 'kubectl apply -f-')
    }
    
    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createConfigMapFromFile(String name, String namespace = '', String filePath) {
        //  kubectl create configmap dev-post-start --from-file=dev-post-start.sh
        String command =
                "kubectl create configmap ${name}${namespace ? " -n ${namespace}" : ''}" +
                        " --from-file=${filePath}" +
                        ' --dry-run=client -oyaml'
        commandExecutor.execute(command, 'kubectl apply -f-')
    }

    void label(String resource, String name, String namespace  = '', Tuple2... keyValues) {
        if (!keyValues) {
            throw new RuntimeException("Missing key-value-pairs")
        }
        String command =
                "kubectl label ${resource} ${name}${namespace ? " -n ${namespace}" : ''} " +
                        '--overwrite ' + // Make idempotent
                        keyValues.collect { "${it.v1}=${it.v2}"}.join(' ')
        commandExecutor.execute(command)
    }
    
    void patch(String resource, String name, String namespace  = '', Map yaml) {
        // We're using a patch file here, instead of a patch JSON (--patch), because of quoting issues
        // ERROR c.c.gitops.utils.CommandExecutor - Stderr: error: unable to parse "'{\"stringData\":": yaml: found unexpected end of stream
        File patchYaml = File.createTempFile('gitops-playground-patch-yaml', '')
        new FileSystemUtils().writeYaml(yaml, patchYaml)
        
        //  kubectl patch secret argocd-secret -p '{"stringData": { "admin.password": "'"${bcryptArgoCDPassword}"'"}}' || true
        String command =
                "kubectl patch ${resource} ${name}${namespace ? " -n ${namespace}" : ''}" +
                        " --patch-file=${patchYaml.absolutePath}"
        commandExecutor.execute(command)
    }
    
    void delete(String resource, String namespace  = '', Tuple2... selectors) {
        if (!selectors) {
            throw new RuntimeException("Missing selectors")
        }
        // kubectl delete secret -n argocd -l owner=helm,name=argocd
        String command =
                "kubectl delete ${resource}${namespace ? " -n ${namespace}" : ''}" +
                        ' --ignore-not-found=true ' + // Make idempotent
                        selectors.collect { "--selector=${it.v1}=${it.v2}"}.join(' ')
        
        commandExecutor.execute(command)
    }
}
