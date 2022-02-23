package com.cloudogu.gop.application

import com.cloudogu.gop.application.modules.ModuleRepository

class GopApplication {

    private Map config

    GopApplication(Map config) {
        this.config = config
    }

    def start() {
        ApplicationConfigurator.populateConfig(config)

        def moduleRepository = new ModuleRepository(config)
        moduleRepository.execute()
    }
}
