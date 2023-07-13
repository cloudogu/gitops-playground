package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.springframework.security.crypto.bcrypt.BCrypt

@Slf4j
@Singleton
@Order(200)
class Mailhog extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/mailhog-helm-values.yaml"
    
    private Map config
    private boolean remoteCluster
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
        this.remoteCluster = this.config.application["remote"]
        this.username = this.config.application["username"]
        this.password = this.config.application["password"]
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return config.features['mail']['active']
    }

    @Override
    void enable() {
        def tmpHelmValues = fileSystemUtils.copyToTempDir(HELM_VALUES_PATH)
        def tmpHelmValuesFolder = tmpHelmValues.parent.toString()
        def tmpHelmValuesFile = tmpHelmValues.fileName.toString()

        if (!remoteCluster) {
            log.debug("Setting mailhog service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpHelmValuesFolder, tmpHelmValuesFile, 
                    "LoadBalancer", "NodePort")
        }

        log.debug("Setting new mailhog credentials")
        String bcryptMailhogPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        String from = "fileContents: \"admin:\$2a\$04\$bM4G0jXB7m7mSv4UT8IuIe3.Bj6i6e2A13ryA0ln.hpyX7NeGQyG.\""
        String to = "fileContents: \"$username:$bcryptMailhogPassword\""

        fileSystemUtils.replaceFileContent(tmpHelmValuesFolder, tmpHelmValuesFile, from, to)

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
