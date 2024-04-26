package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.DockerImageParser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(400)
class ExternalSecretsOperator extends Feature {
    private Map config
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer

    ExternalSecretsOperator(
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
        return config.features['secrets']['active']
    }

    @Override
    void enable() {
        def helmConfig = config['features']['secrets']['externalSecrets']['helm']
        def helmValuesPath = fileSystemUtils.copyToTempDir('applications/cluster-resources/secrets/external-secrets/values.yaml')
        def helmValuesYaml = fileSystemUtils.readYaml(helmValuesPath)
        if (helmConfig['image']) {
            log.debug("Setting custom ESO image as requested for external-secrets-operator")
            def image = DockerImageParser.parse(helmConfig['image'] as String)
            MapUtils.deepMerge([
                    image: [
                            repository: image.getRegistryAndRepositoryAsString(),
                            tag       : image.tag
                    ]
            ], helmValuesYaml)
        }
        if (helmConfig['certControllerImage']) {
            log.debug("Setting custom cert-controller image as requested for external-secrets-operator")
            def image = DockerImageParser.parse(helmConfig['certControllerImage'] as String)
            MapUtils.deepMerge([
                    certController: [
                            image: [
                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }
        if (helmConfig['webhookImage']) {
            log.debug("Setting custom webhook image as requested for external-secrets-operator")
            def image = DockerImageParser.parse(helmConfig['webhookImage'] as String)
            MapUtils.deepMerge([
                    webhook: [
                            image: [
                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }
        fileSystemUtils.writeYaml(helmValuesYaml, helmValuesPath.toFile())

        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                "externalsecretsoperator",
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'secrets',
                'external-secrets',
                helmValuesPath
        )
    }
}
