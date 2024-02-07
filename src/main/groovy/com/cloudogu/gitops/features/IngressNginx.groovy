package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
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
        def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(),
                [:
                   // once we introduce new parameters, we pass them to template here
                ]).toPath()

        def helmConfig = config['features']['ingressNginx']['helm']

        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                'ingress-nginx',
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'kube-system',
                'ingress-nginx',
                tmpHelmValues)

    }

}
