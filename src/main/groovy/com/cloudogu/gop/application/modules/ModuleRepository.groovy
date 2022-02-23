package com.cloudogu.gop.application.modules

import com.cloudogu.gop.application.modules.metrics.MetricsModule


class ModuleRepository {

    private Map<String, String> config
    private List<GopModule> allModules

    ModuleRepository(Map<String, String> config) {
        this.config = config
        allModules = new ArrayList<>()
        registerAllModules()
    }

    // Registered modules are chronologically sensitive. This means, that the first registered module will be first to run and the last module registered will be the last to run
    def registerAllModules() {
        allModules.add(new MetricsModule(config.application as Map, config.modules["argocd"]["url"] as String, config.modules["metrics"] as boolean, config.scmm as Map))
    }

    def execute() {
        allModules.forEach(module -> {
            module.run()
        })
    }
}
