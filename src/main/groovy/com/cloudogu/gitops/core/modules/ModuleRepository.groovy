package com.cloudogu.gitops.core.modules


import groovy.util.logging.Slf4j

@Slf4j
class ModuleRepository {

    private Map config
    private List<Module> allModules

    ModuleRepository(Map config) {
        this.config = config
        allModules = new ArrayList<>()
        registerAllModules()
    }

    void execute() {
        log.info("Starting to execute all gop modules")
        allModules.forEach(module -> {
            module.run()
        })
        log.info("Finished running all gop modules")
    }

    // Registered modules are chronologically sensitive. This means, that the first registered module will be first to run and the last module registered will be the last to run
    private void registerAllModules() {
        log.debug("Registering gop modules")
        com.cloudogu.gitops.core.clients.git.GitClient gitClient = new com.cloudogu.gitops.core.clients.git.GitClient(config)

        allModules.add(new com.cloudogu.gitops.core.modules.metrics.MetricsModule(config, gitClient))
    }
}
