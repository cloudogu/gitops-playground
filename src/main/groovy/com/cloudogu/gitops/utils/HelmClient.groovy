package com.cloudogu.gitops.utils


import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class HelmClient {

    private CommandExecutor commandExecutor

    HelmClient(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor
    }

    String addRepo(String repoName, String url) {
        commandExecutor.execute("helm repo add ${repoName} ${url}").stdOut
    }
    
    String dependencyBuild(String path) {
        String command =  "helm dependency build ${path}"
        commandExecutor.execute(command).stdOut
    }
    
    String upgrade(String release, String chartOrPath, Map args) {
        String command =  "helm upgrade -i ${release} ${chartOrPath} " +
                "${args.version? "--version ${args.version} " : ''}" +
                "${args.values? "--values ${args.values} " : ''}" +
                "${args.namespace? "--namespace ${args.namespace} " : ''}"
        commandExecutor.execute(command).stdOut
    }
}
