package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class Vault extends Feature {
    static final String HELM_VALUES_PATH = "system/secrets/vault/values.yaml"

    private Map config
    private FileSystemUtils fileSystemUtils
    private HelmClient helmClient
    private File tmpHelmValues

    Vault(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(),
          HelmClient helmClient = new HelmClient()) {
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient

        tmpHelmValues = File.createTempFile('gitops-playground-control-app', '')
        tmpHelmValues.deleteOnExit()
    }

    @Override
    boolean isEnabled() {
        return config.features['secrets']['active']
    }

    @Override
    void enable() {
        // Note that some specific configuration steps are implemented in ArgoCD

        Map yaml = fileSystemUtils.readYaml(Path.of fileSystemUtils.rootDir, HELM_VALUES_PATH)

        if (!config.application['remote']) {
            log.debug("Setting Vault service.type to NodePort since it is not running in a remote cluster")
            yaml['ui']['serviceType'] = 'NodePort'

        }

        String vaultMode = config['features']['secrets']['vault']['mode']
        if (vaultMode == 'dev') {
            log.debug("!! Enabling vault dev mode. ONLY FOR TESTING !!")
            MapUtils.deepMerge(
                    [
                            server: [
                                    dev: [
                                            enabled: true
                                    ]
                            ]
                    ], yaml)
            // Create the secrets referenced by SecretStores in ArgoCD control-app
            // TODO set server.dev.devRootToken to configured password
            
            // TODO build webhook that creates secret for example app
            // kubectl exec vault-0 -it -- vault kv put secret/mysecret mykey=mypass
        }

        log.trace("Helm yaml to be applied: ${yaml}")
        fileSystemUtils.writeYaml(yaml, tmpHelmValues)

        def helmConfig = config['features']['secrets']['vault']['helm']
        helmClient.addRepo(getClass().simpleName, helmConfig['repoURL'] as String)
        helmClient.upgrade('vault', "${getClass().simpleName}/${helmConfig['chart']}",
                helmConfig['version'] as String,
                [namespace: 'secrets',
                 values   : "${tmpHelmValues.toString()}"])
    }
}
