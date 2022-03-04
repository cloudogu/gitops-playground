package com.cloudogu.gop.application

import com.cloudogu.gop.application.modules.ModuleRepository
import groovy.util.logging.Slf4j

@Slf4j
class GopApplication {

    private ModuleRepository moduleRepository

    GopApplication(ModuleRepository moduleRepository) {
        this.moduleRepository = moduleRepository
    }

    def start() {
        log.info("Starting Gop Application")
        moduleRepository.execute()
        log.info("Gop Application installed")
    }
}
