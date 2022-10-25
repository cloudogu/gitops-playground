package com.cloudogu.gitops.utils


import groovy.util.logging.Slf4j

@Slf4j
class HelmClient {

    private CommandExecutor commandExecutor

    HelmClient(CommandExecutor commandExecutor = new CommandExecutor()) {
        this.commandExecutor = commandExecutor
    }

    String addRepo(String repoName, String url) {
        commandExecutor.execute("helm repo add ${repoName} ${url}")
    }
    String upgrade(String release, String chart, String version, Map args) {
        String command =  "helm upgrade -i ${release} ${chart} --version=${version} " +
                "${args.values? "--values ${args.values} " : ''}" +
                "${args.namespace? "--namespace ${args.namespace} " : ''}"
        commandExecutor.execute(command)
    }
}
