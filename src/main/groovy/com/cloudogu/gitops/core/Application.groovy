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
        moduleRepository.execute()
        log.info("Application finished")
    }
}
