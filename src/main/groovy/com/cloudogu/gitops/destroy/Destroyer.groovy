package com.cloudogu.gitops.destroy

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Singleton
@Slf4j
class Destroyer implements DestructionHandler {

    private final List<DestructionHandler> destructionHandlers

    Destroyer(List<DestructionHandler> destructionHandlers) {
        this.destructionHandlers = destructionHandlers
    }

    @Override
    void destroy() {
        log.info("Start destroying")
        for (def handler in destructionHandlers) {
            log.info("Running handler $handler.class.simpleName")
            handler.destroy()
        }
        log.info("Finished destroying")
    }

    List<DestructionHandler> getDestructionHandlers() {
        return destructionHandlers
    }
}
