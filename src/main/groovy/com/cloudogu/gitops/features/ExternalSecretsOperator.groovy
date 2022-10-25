package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import groovy.util.logging.Slf4j

@Slf4j
class ExternalSecretsOperator extends Feature {
    private Map config
    private FileSystemUtils fileSystemUtils
    private K8sClient k8sClient
    private HelmClient helmClient
    
    ExternalSecretsOperator(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(), 
                            K8sClient k8sClient = new K8sClient(), HelmClient helmClient = new HelmClient()) {
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.helmClient = helmClient
    }

    @Override
    boolean isEnabled() {
        return config.features["vault"]
    }

    @Override
    void enable() {
        helmClient.addRepo('external-secrets', 'https://charts.external-secrets.io')
        helmClient.upgrade('external-secrets', 'external-secrets/external-secrets', '0.6.0', 
                [namespace: 'secrets', 
                 values: "${fileSystemUtils.rootDir}/system/secrets/external-secrets/values.yaml"])
        // TODO when do we apply those? With an example project or cluster wide?
        //k8sClient.applyYaml(fileSystemUtils.rootDir + "/system/secrets/external-secrets/secret-store.yaml")
    }
}
