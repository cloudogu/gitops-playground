package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
@Order(500)
class Vault extends Feature {
    static final String VAULT_START_SCRIPT_PATH = '/applications/cluster-resources/secrets/vault/dev-post-start.ftl.sh'


    private Map config
    private FileSystemUtils fileSystemUtils
    private Path tmpHelmValues
    private K8sClient k8sClient
    private DeploymentStrategy deployer

    Vault(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            K8sClient k8sClient,
            DeploymentStrategy deployer
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient

        tmpHelmValues = fileSystemUtils.createTempFile()
    }

    @Override
    boolean isEnabled() {
        return config.features['secrets']['active']
    }

    @Override
    void enable() {
        // Note that some specific configuration steps are implemented in ArgoCD

        def helmConfig = config['features']['secrets']['vault']['helm']

        Map yaml = [
                ui: [
                        enabled: true,
                        serviceType: "LoadBalancer",
                        externalPort: 80
                ],
                injector: [
                        enabled: false
                ],
                server: [
                        resources: [
                                limits: [
                                        memory: '200Mi',
                                        cpu: '500m'
                                ],
                                requests: [
                                        memory: '100Mi',
                                        cpu: '50m'
                                ]
                        ]
                ]
        ]

        if (!config.application['remote']) {
            log.debug("Setting Vault service.type to NodePort since it is not running in a remote cluster")
            yaml['ui']['serviceType'] = 'NodePort'
            yaml['ui']['serviceNodePort'] = 8200
        }

        if (config.features['secrets']['vault']['url']) {
            def url = new URL(config.features['secrets']['vault']['url'] as String)
            MapUtils.deepMerge([
                   server: [
                           ingress: [
                                   enabled: true,
                                   hosts: [
                                           [host: url.host],
                                   ],
                           ]
                   ]
            ], yaml)
        }

        if (helmConfig['image']) {
            log.debug("Setting custom image as requested for vault")
            def image = DockerImageParser.parse(helmConfig['image'] as String)
            MapUtils.deepMerge([
                    server: [
                            image: [

                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
                            ]
                    ]
            ], yaml)
        }

        String vaultMode = config['features']['secrets']['vault']['mode']
        if (vaultMode == 'dev') {
            log.debug("WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n" +
                    "and starts unsealed with a single unseal key. ")
            
            // Create config map from init script
            // Init script creates/authorizes secrets, users, service accounts, etc.
            def vaultPostStartConfigMap = 'vault-dev-post-start'
            def vaultPostStartVolume = 'dev-post-start'

            def namePrefix = config.application['namePrefix']

            def templatedFile = fileSystemUtils.copyToTempDir(fileSystemUtils.getRootDir() + VAULT_START_SCRIPT_PATH)
            def postStartScript = new TemplatingEngine().replaceTemplate(templatedFile.toFile(), [namePrefix: namePrefix])
            
            log.debug("Creating namespace for vault, so it can add its secrets there")
            k8sClient.createNamespace("secrets")
            k8sClient.createConfigMapFromFile(vaultPostStartConfigMap, "secrets", postStartScript.absolutePath)

            MapUtils.deepMerge(
                    [
                            server: [
                                    dev: [
                                            enabled: true,
                                            // Don't create fixed devRootToken token (more secure when remote cluster) 
                                            // -> Root token can be found on the log if needed
                                            devRootToken: UUID.randomUUID()
                                    ],
                                    // Mount init script via config-map 
                                    volumes: [
                                            [
                                                    name: vaultPostStartVolume,
                                                    configMap: [
                                                            name: vaultPostStartConfigMap,
                                                            // Make executable
                                                            defaultMode: 0774
                                                    ]
                                            ]
                                    ],
                                    volumeMounts: [
                                            [
                                                    mountPath: '/var/opt/scripts',
                                                    name: vaultPostStartVolume,
                                                    readOnly: true
                                            ]
                                    ],
                                    // Execute init script as post start hook
                                    postStart: [
                                            '/bin/sh',
                                            '-c',
                                                "USERNAME=${config['application']['username']} " +
                                                "PASSWORD=${config['application']['password']} " +
                                                "ARGOCD=${config.features['argocd']['active']} " +
                                                    // Write script output to file for easier debugging
                                                    "/var/opt/scripts/${postStartScript.name} 2>&1 | tee /tmp/dev-post-start.log"
                                    ],
                            ]
                    ], yaml)
        }

        log.trace("Helm yaml to be applied: ${yaml}")
        fileSystemUtils.writeYaml(yaml, tmpHelmValues.toFile())

        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                'vault',
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'secrets',
                'vault',
                tmpHelmValues
        )
    }
}
