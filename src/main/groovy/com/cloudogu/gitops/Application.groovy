package com.cloudogu.gitops


import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class Application {

    private final List<Feature> features

    Application(
            List<Feature> features
    ) {
        // Order is important. Enforced by @Order-Annotation on the Singletons
        this.features = features
    }

    def start() {
        log.debug("Starting Application")
        features.forEach(feature -> {
            feature.install()
        })
        log.debug("Application finished")
    }

    List<Feature> getFeatures() {
        return features
    }
}
