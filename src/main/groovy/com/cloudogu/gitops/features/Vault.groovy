package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.*
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
@Order(500)
class Vault extends Feature implements FeatureWithImage {
    static final String VAULT_START_SCRIPT_PATH = '/applications/cluster-resources/secrets/vault/dev-post-start.ftl.sh'
    static final String HELM_VALUES_PATH = 'applications/cluster-resources/secrets/vault/values.ftl.yaml'

    String namespace =  "${config.application.namePrefix}secrets"
    Config config
    K8sClient k8sClient

    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils

    Vault(
            Config config,
            FileSystemUtils fileSystemUtils,
            K8sClient k8sClient,
            DeploymentStrategy deployer,
            AirGappedUtils airGappedUtils
    ) {
        this.deployer = deployer
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
    }

    @Override
    boolean isEnabled() {
        return config.features.secrets.active
    }

    @Override
    void enable() {
        def namePrefix = config.application.namePrefix
        // Note that some specific configuration steps are implemented in ArgoCD
        def helmConfig = config.features.secrets.vault.helm

        def templatedMap = templateToMap(HELM_VALUES_PATH, [
                namePrefix: namePrefix,
                host   : config.features.secrets.vault.url ? new URL(config.features.secrets.vault.url as String).host : '',
                config : config,
                // Allow for using static classes inside the templates
                statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ])

        String vaultMode = config.features.secrets.vault.mode
        if (vaultMode == 'dev') {
            log.debug('WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n' +
                    'and starts unsealed with a single unseal key. ')

            // Create config map from init script
            // Init script creates/authorizes secrets, users, service accounts, etc.
            def vaultPostStartConfigMap = 'vault-dev-post-start'
            def vaultPostStartVolume = 'dev-post-start'

            def templatedFile = fileSystemUtils.copyToTempDir(fileSystemUtils.getRootDir() + VAULT_START_SCRIPT_PATH)
            def postStartScript = new TemplatingEngine().replaceTemplate(templatedFile.toFile(), [namePrefix: config.application.namePrefix])

            log.debug('Creating namespace for vault, so it can add its secrets there')
            k8sClient.createNamespace(namespace)
            k8sClient.createConfigMapFromFile(vaultPostStartConfigMap, namespace, postStartScript.absolutePath)

            templatedMap = MapUtils.deepMerge(
                    [
                            server: [
                                    dev         : [
                                            enabled     : true,
                                            // Don't create fixed devRootToken token (more secure when remote cluster) 
                                            // -> Root token can be found on the log if needed
                                            devRootToken: UUID.randomUUID()
                                    ],
                                    // Mount init script via config-map 
                                    volumes     : [
                                            [
                                                    name     : vaultPostStartVolume,
                                                    configMap: [
                                                            name       : vaultPostStartConfigMap,
                                                            // Make executable
                                                            defaultMode: 0774
                                                    ]
                                            ]
                                    ],
                                    volumeMounts: [
                                            [
                                                    mountPath: '/var/opt/scripts',
                                                    name     : vaultPostStartVolume,
                                                    readOnly : true
                                            ]
                                    ],
                                    // Execute init script as post start hook
                                    postStart   : [
                                            '/bin/sh',
                                            '-c',
                                            "USERNAME=${config.application.username} " +
                                                    "PASSWORD=${config.application.password} " +
                                                    "ARGOCD=${config.features.argocd.active} " +
                                                    // Write script output to file for easier debugging
                                                    "/var/opt/scripts/${postStartScript.name} 2>&1 | tee /tmp/dev-post-start.log"
                                    ],
                            ]
                    ], templatedMap)
        }

        templatedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
        log.trace("Helm yaml to be applied: ${templatedMap}")
        def tempValuesPath = fileSystemUtils.writeTempFile(templatedMap)

        if (config.application.mirrorRepos) {
            log.debug('Mirroring repos: Deploying vault from local git repo')

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config.features.secrets.vault.helm as Config.HelmConfig)

            String vaultVersion =
                    new YamlSlurper().parse(Path.of(config.application.localHelmChartFolder + '/' + helmConfig.chart,
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'vault',
                    '.',
                    vaultVersion,
                    namespace,
                    'vault',
                    tempValuesPath, DeploymentStrategy.RepoType.GIT
            )
        } else {
            deployer.deployFeature(
                    helmConfig.repoURL,
                    'vault',
                    helmConfig.chart,
                    helmConfig.version,
                    namespace,
                    'vault',
                    tempValuesPath
            )
        }
    }

    private URI getScmmUri() {
        if (config.scmm.internal) {
            new URI("http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm")
        } else {
            new URI("${config.scmm.url}")
        }
    }
}