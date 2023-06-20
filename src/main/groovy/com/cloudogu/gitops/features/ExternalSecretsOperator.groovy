package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.features.deployment.Deployer
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.DockerImageParser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j

@Slf4j
class ExternalSecretsOperator extends Feature {
    private Map config
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer

    ExternalSecretsOperator(
            Map config,
            FileSystemUtils fileSystemUtils = new FileSystemUtils(),
            DeploymentStrategy deployer = new Deployer(config)
    ) {
        this.deployer = deployer
        this.config = config
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
        if (null != helmConfig['image']) {
            def image = DockerImageParser.parse(helmConfig['image'] as String)
            MapUtils.deepMerge([
                    image: [
                            repository: image.repository,
                            tag       : image.tag
                    ]
            ], helmValuesYaml)
        }
        if (null != helmConfig['certControllerImage']) {
            def image = DockerImageParser.parse(helmConfig['certControllerImage'] as String)
            MapUtils.deepMerge([
                    certController: [
                            image: [
                                    repository: image.repository,
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }
        if (null != helmConfig['webhookImage']) {
            def image = DockerImageParser.parse(helmConfig['webhookImage'] as String)
            MapUtils.deepMerge([
                    webhook: [
                            image: [
                                    repository: image.repository,
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
