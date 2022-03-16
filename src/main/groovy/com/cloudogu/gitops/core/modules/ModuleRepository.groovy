package com.cloudogu.gitops.core.modules

import com.cloudogu.gitops.core.clients.git.GitClient
import com.cloudogu.gitops.core.modules.metrics.MetricsModule
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
        log.info("Starting to execute all modules")
        allModules.forEach(module -> {
            module.run()
        })
        log.info("Finished running all modules")
    }

    // Registered modules are chronologically sensitive. 
    // This means, that the first registered module will be first and the last module registered will be the last to run
    private void registerAllModules() {
        log.debug("Registering modules")
        GitClient gitClient = new GitClient(config)

        allModules.add(new MetricsModule(config, gitClient))
    }
}
