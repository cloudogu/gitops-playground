package com.cloudogu.gop.application.modules

import com.cloudogu.gop.application.modules.metrics.MetricsModule
import groovy.util.logging.Slf4j

@Slf4j
class ModuleRepository {

    private Map<String, String> config
    private List<GopModule> allModules

    ModuleRepository(Map<String, String> config) {
        this.config = config
        allModules = new ArrayList<>()
        registerAllModules()
    }

    // Registered modules are chronologically sensitive. This means, that the first registered module will be first to run and the last module registered will be the last to run
    void registerAllModules() {
        log.debug("Registering gop modules")

        allModules.add(getMetricsModule())
    }

    void execute() {
        log.info("Starting to execute all gop modules")
        allModules.forEach(module -> {
            module.run()
        })
        log.info("Finished running all gop modules")
    }

    private MetricsModule getMetricsModule() {
        log.debug("Configuring metrics module")
        Map applicationConfig = config.subMap(["application"])
        String argocdUrl = config.modules["argocd"]["url"]
        boolean metrics = config.modules["metrics"]
        Map scmmConfig = config.subMap(["scmm"])

        return new MetricsModule(applicationConfig, argocdUrl, metrics, scmmConfig)
    }
}
