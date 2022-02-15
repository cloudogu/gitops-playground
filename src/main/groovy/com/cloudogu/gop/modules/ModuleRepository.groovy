package com.cloudogu.gop.modules


import com.cloudogu.gop.modules.metrics.MetricsModule

class ModuleRepository {

    List<GopModule> allModules

    ModuleRepository() {
        allModules = new ArrayList<>()
        registerAllModules()
    }

    // Registered modules are chronologically sensitive. This means, that the first registered module will be first to run and the last module registered will be the last to run
    def registerAllModules() {
        allModules.add(new MetricsModule())
    }

    def execute() {
        allModules.forEach(module -> {
            module.run()
        })
    }
}
