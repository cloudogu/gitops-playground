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
            log.debug("WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n" +
                    "and starts unsealed with a single unseal key. The root token is already\n" +
                    "authenticated to the CLI, so you can immediately begin using Vault.")
            MapUtils.deepMerge(
                    [
                            server: [
                                    dev: [
                                            enabled: true,
                                            devRootToken: config['application']['password']
                                    ],
                                    postStart: [
                                            '/bin/sh',
                                            '-c',
                                              'vault kv put secret/staging nginx-secret=staging-secret && ' +
                                              'vault kv put secret/production nginx-secret=production-secret'
                                    ]
                            ]
                    ], yaml)
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
