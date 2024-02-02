package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.springframework.security.crypto.bcrypt.BCrypt

@Slf4j
@Singleton
@Order(200)
class Mailhog extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/mailhog-helm-values.ftl.yaml"

    private Map config
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer

    Mailhog(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.username = this.config.application["username"]
        this.password = this.config.application["password"]
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return config.features['mail']['mailhog']
    }

    @Override
    void enable() {
        String bcryptMailhogPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [
                mail: [
                        // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                        host: config.features['mail']['mailhogUrl'] ? new URL(config.features['mail']['mailhogUrl'] as String).host : "",
                ],
                image: config['features']['mail']['helm']['image'] as String,
                isRemote: config.application['remote'],
                username: username,
                passwordCrypt: bcryptMailhogPassword,
        ]).toPath()

        def helmConfig = config['features']['mail']['helm']
        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                'mailhog',
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'monitoring',
                'mailhog',
                tmpHelmValues)
    }
}
