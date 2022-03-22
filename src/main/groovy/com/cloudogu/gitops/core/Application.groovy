package com.cloudogu.gitops.core

import com.cloudogu.gitops.core.modules.ModuleRepository
import groovy.util.logging.Slf4j

@Slf4j
class Application {

    private ModuleRepository moduleRepository

    Application(ModuleRepository moduleRepository) {
        this.moduleRepository = moduleRepository
    }

    def start() {
        log.info("Starting Application")
        try {
            moduleRepository.execute()
        } catch (Throwable throwable) {
            log.error("Application failed", throwable)
            // Make sure to exit with error. Otherwise 0 seems to be returned.
            System.exit(1)
        }
        log.info("Application finished")
    }
}
