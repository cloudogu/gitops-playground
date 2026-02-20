package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(500)
class Vault extends Feature implements FeatureWithImage {
    static final String VAULT_START_SCRIPT_PATH = "argocd/cluster-resources/apps/vault/templates/dev-post-start.ftl.sh"
    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/vault/templates/values.ftl.yaml"

    String namespace = "${config.application.namePrefix}secrets"
    Config config
    K8sClient k8sClient

    Vault(
            Config config,
            FileSystemUtils fileSystemUtils,
            K8sClient k8sClient,
            DeploymentStrategy deployer,
            AirGappedUtils airGappedUtils,
            GitHandler gitHandler
    ) {
        this.deployer = deployer
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
        this.gitHandler = gitHandler
    }

    @Override
    boolean isEnabled() {
        return config.features.secrets.active
    }

    @Override
    void enable() {
        // Note that some specific configuration steps are implemented in ArgoCD
        def helmConfig = config.features.secrets.vault.helm

        addHelmValuesData("host", config.features.secrets.vault.url ? new URL(config.features.secrets.vault.url as String).host : '')

        String vaultMode = config.features.secrets.vault.mode
        if (vaultMode == 'dev') {
            log.debug('WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n' +
                    'and starts unsealed with a single unseal key. ')

            // Create config map from init script
            // Init script creates/authorizes secrets, users, service accounts, etc.
            def vaultPostStartConfigMap = 'vault-dev-post-start'
            def vaultPostStartVolume = 'dev-post-start'

            def templatedFile = fileSystemUtils.copyToTempDir(fileSystemUtils.getRootDir() + "/"+VAULT_START_SCRIPT_PATH)
            def postStartScript = new TemplatingEngine().replaceTemplate(templatedFile.toFile(), [namePrefix: config.application.namePrefix])

            log.debug('Creating namespace for vault, so it can add its secrets there')
            k8sClient.createNamespace(namespace)
            k8sClient.createConfigMapFromFile(vaultPostStartConfigMap, namespace, postStartScript.absolutePath)

            addHelmValuesData("dev", [
                    rootToken: UUID.randomUUID(),
                    vaultPostStartConfigMap: vaultPostStartConfigMap,
                    vaultPostStartVolume: vaultPostStartVolume,
                    postStartScriptName: postStartScript.name
            ])
        }

        deployHelmChart('vault', 'vault', namespace, helmConfig, HELM_VALUES_PATH, config)
    }
}