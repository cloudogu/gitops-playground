package com.cloudogu.gitops

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.TemplatingEngine
import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class Application {

    final List<Feature> features
    final Config config
    final K8sClient k8sClient

    Application(Config config, K8sClient k8sClient,
                List<Feature> features
    ) {
        this.config = config
        // Order is important. Enforced by @Order-Annotation on the Singletons
        this.features = features
        this.k8sClient = k8sClient
    }

    def start() {
        log.debug("Starting Application")

        setNamespaceListToConfig(config)
        // if set, stores configuration in a secret.
        storeGopInformationInSecret(config)

        features.forEach(feature -> {
            feature.validate()
        })
        features.forEach(feature -> {
            feature.install()
        })
        log.debug("Application finished")
    }

    private void storeGopInformationInSecret(Config config) {
        if (!config.application.gopNamespace.isEmpty()) {

            String namespace = "${config.application.namePrefix}${config.application.gopNamespace}"
            k8sClient.createNamespace(namespace)
            k8sClient.createSecret('generic', 'gop-configuration', namespace,
                    new Tuple2('gop-initial-password', config.DEFAULT_ADMIN_PW),
                    new Tuple2('gop-config', config.toYaml(true)))
        }
    }

    List<Feature> getFeatures() {
        return features
    }

    void setNamespaceListToConfig(Config config) {
        LinkedHashSet<String> dedicatedNamespaces = new LinkedHashSet<>()
        LinkedHashSet<String> tenantNamespaces = new LinkedHashSet<>()
        def engine = new TemplatingEngine()

        config.content.namespaces.each { String ns ->
            tenantNamespaces.add(engine.template(ns, [
                    config : config,
                    // Allow for using static classes inside the templates
                    statics: new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
            ]))
        }
        config.content.namespaces = tenantNamespaces.toList()

        //iterates over all FeatureWithImages and gets their namespaces
        dedicatedNamespaces.addAll(this.features
                .collect { it.activeNamespaceFromFeature }
                .findAll { it }
                .unique()
                .collect { "${it}".toString() })

        config.application.namespaces.dedicatedNamespaces = dedicatedNamespaces
        config.application.namespaces.tenantNamespaces = tenantNamespaces
        log.debug("Active namespaces retrieved: {}", config.application.namespaces.activeNamespaces)
    }
}