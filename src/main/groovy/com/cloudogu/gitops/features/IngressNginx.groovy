package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(150)
class IngressNginx extends Feature {
    static final String HELM_VALUES_PATH = "applications/cluster-resources/ingress-nginx-helm-values.ftl.yaml"

    private Map config
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer

    IngressNginx(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return config.features['ingressNginx']['active']
    }

    @Override
    void enable() {
        
        def templatedMap = new YamlSlurper().parseText(
                new TemplatingEngine().template(new File(HELM_VALUES_PATH),
                    [:
                       // once we introduce new parameters, we pass them to template here
                    ])) as Map

        def valuesFromConfig = config['features']['ingressNginx']['helm']['values'] as Map
        
        def mergedMap = MapUtils.deepMerge(valuesFromConfig, templatedMap)

        def tmpHelmValues = fileSystemUtils.createTempFile()
        // Note that YAML builder seems to use double quotes to escape strings. So for example:
        // This:     log-format-upstream: '..."$request"...'
        // Becomes:  log-format-upstream: "...\"$request\"..."
        // Harder to read but same payload. Not sure if we can do something about it.
        fileSystemUtils.writeYaml(mergedMap, tmpHelmValues.toFile())

        def helmConfig = config['features']['ingressNginx']['helm']

        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                'ingress-nginx',
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'ingress-nginx',
                'ingress-nginx',
                tmpHelmValues)

    }
}
