package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.util.logging.Slf4j

@Slf4j
class ExternalSecretsOperator extends Feature {
    private Map config
    private FileSystemUtils fileSystemUtils
    private HelmClient helmClient
    
    ExternalSecretsOperator(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(), 
                            HelmClient helmClient = new HelmClient()) {
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient
    }

    @Override
    boolean isEnabled() {
        return config.features['secrets']['active']
    }

    @Override
    void enable() {
        def helmConfig = config['features']['secrets']['externalSecrets']['helm']
        helmClient.addRepo(getClass().simpleName, helmConfig['repoURL'] as String)
        helmClient.upgrade('external-secrets', "${getClass().simpleName}/${helmConfig['chart']}",
                [namespace: 'secrets',
                 version: helmConfig['version'],
                 values: "${fileSystemUtils.rootDir}/system/secrets/external-secrets/values.yaml"])
    }
}
