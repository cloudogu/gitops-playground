package com.cloudogu.gitops


import com.cloudogu.gitops.argocd.ArgoCD
import groovy.util.logging.Slf4j

@Slf4j
class Application {

    Map config = config
    List<Feature> features

    Application(Map config) {
        this.config = config
        this.features = registerFeatures()
    }

    def start() {
        log.info("Starting Application")
        features.forEach(feature -> {
            feature.install()
        })
        log.info("Application finished")
    }

    // Registered features are chronologically sensitive. 
    // This means, that the first registered feature will be first and the last feature registered will be the last to run
    private List<Feature> registerFeatures() {
        List<Feature> features = []
        features.add(new ArgoCD(config))
        return features
    }
}
