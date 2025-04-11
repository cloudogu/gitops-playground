package com.cloudogu.gitops

import com.cloudogu.gitops.config.Config
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class Application {

    final List<Feature> features
    final Config config

    Application(Config config,
                List<Feature> features
    ) {
        this.config = config
        // Order is important. Enforced by @Order-Annotation on the Singletons
        this.features = features
    }

    def start() {
        log.debug("Starting Application")

        setNamespaceListToConfig(config)
        features.forEach(feature -> {
            feature.install()
        })
        log.debug("Application finished")
    }

    List<Feature> getFeatures() {
        return features
    }

    void setNamespaceListToConfig(Config config) {
        Set<String> namespaces = new HashSet<>()
        String namePrefix = config.application.namePrefix

        if (config.features.argocd.active) {
            namespaces.addAll(Arrays.asList(
                    namePrefix + "example-apps-staging",
                    namePrefix + "example-apps-production"
            ))
        }

        //iterates over all FeatureWithImages and gets their namespaces
        namespaces.addAll(this.features
                .collect { it.activeNamespaceFromFeature }
                .findAll { it }
                .unique()
                .collect { "${it}".toString() })

        log.debug("Active namespaces retrieved: {}", namespaces);
        config.application.activeNamespaces = namespaces.toList()
    }
}