package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.features.deployment.Deployer
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j

import java.nio.file.Path

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
        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                "externalsecretsoperator",
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'secrets',
                'external-secrets',
                Path.of("${fileSystemUtils.rootDir}/system/secrets/external-secrets/values.yaml")
        )
    }
}
