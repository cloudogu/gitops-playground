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
        helm(['repo', 'add', repoName, url ])
    }
    
    String dependencyBuild(String path) {
        helm(['dependency', 'build', path ])
    }
    
    String upgrade(String release, String chartOrPath, Map args) {
        helm(['upgrade', '-i', release, chartOrPath, '--create-namespace' ], args)
    }
    
    String template(String release, String chartOrPath, Map args = [:]) {
        helm(['template', release, chartOrPath ], args)
    }
    
    private String helm(List<String> verbAndParams, Map args = [:]) {
        List<String> command = ['helm'] + verbAndParams 
        
        for (entry in args) {
            String key = entry.key
            String value = entry.value
            command += "--${key}".toString()
            command += value
        }

        commandExecutor.execute(command as String[]).stdOut
    }

    String uninstall(String release, String namespace) {
        String[] command = ["helm", "uninstall", release, '--namespace', namespace]

        commandExecutor.execute(command).stdOut
    }
}
