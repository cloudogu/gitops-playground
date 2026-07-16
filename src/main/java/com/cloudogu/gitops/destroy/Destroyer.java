package com.cloudogu.gitops.destroy;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Singleton
public class Destroyer {

    private static final Logger log = LoggerFactory.getLogger(Destroyer.class);

    private final List<DestructionHandler> destructionHandlers;

    public Destroyer(List<DestructionHandler> destructionHandlers) {
        this.destructionHandlers = destructionHandlers;
    }

    public void destroy() {
        log.info("Start destroying");
        for (DestructionHandler handler : destructionHandlers) {
            log.info("Running handler {}", handler.getClass().getSimpleName());
            handler.destroy();
        }
        log.info("Finished destroying");
    }

    public List<DestructionHandler> getDestructionHandlers() {
        return destructionHandlers;
    }
}
