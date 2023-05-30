package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.util.logging.Slf4j
import org.springframework.security.crypto.bcrypt.BCrypt

@Slf4j
class Mailhog extends Feature {

    static final String HELM_VALUES_PATH = "system/mailhog-helm-values.yaml"
    
    private Map config
    private boolean remoteCluster
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    HelmClient helmClient

    Mailhog(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(),
            HelmClient helmClient = new HelmClient()) {
        this.config = config
        this.remoteCluster = config.application["remote"]
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient
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
        helmClient.addRepo(getClass().simpleName, helmConfig['repoURL'] as String)
        helmClient.upgrade('mailhog', "${getClass().simpleName}/${helmConfig['chart']}",
                [namespace: 'monitoring',
                 version: helmConfig['version'],
                 values: "${tmpHelmValues.toString()}"])
    }
}
