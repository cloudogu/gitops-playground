package com.cloudogu.gop.application

import com.cloudogu.gop.application.modules.ModuleRepository
import groovy.util.logging.Slf4j

@Slf4j
class GopApplication {

    private Map config

    GopApplication(Map<String, Serializable> config) {
        this.config = config
    }

    def start() {
        log.info("Starting Gop Application")
        ApplicationConfigurator.populateConfig(config)

        def moduleRepository = new ModuleRepository(config)
        moduleRepository.execute()
        log.info("Gop Application installed")
    }
}
