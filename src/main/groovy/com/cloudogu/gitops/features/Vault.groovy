package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Configuration
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
    
    String namespace = 'secrets'
    Map config
    K8sClient k8sClient
    
    private FileSystemUtils fileSystemUtils
    private Path tmpHelmValues
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils

    Vault(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            K8sClient k8sClient,
            DeploymentStrategy deployer,
            AirGappedUtils airGappedUtils
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils

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

        def yaml =  new YamlSlurper().parseText(
                new TemplatingEngine().template(new File(HELM_VALUES_PATH), [
                host: config.features['secrets']['vault']['url'] ? new URL(config.features['secrets']['vault']['url'] as String).host : "",
                config: config,
                // Allow for using static classes inside the templates
                statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
            ])) as Map

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

        if (config.application['mirrorRepos']) {
            log.debug("Mirroring repos: Deploying vault from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config['features']['secrets']['vault']['helm'] as Map)

            String vaultVersion =
                    new YamlSlurper().parse(Path.of("${config.application['localHelmChartFolder']}/${helmConfig['chart']}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    "vault",
                    '.',
                    vaultVersion,
                    namespace,
                    'vault',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT
            )
        } else {
            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'vault',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    namespace,
                    'vault',
                    tmpHelmValues
            )
        }
    }
    private URI getScmmUri() {
        if (config.scmm['internal']) {
            new URI('http://scmm-scm-manager.default.svc.cluster.local/scm')
        } else {
            new URI("${config.scmm['url']}/scm")
        }
    }
}
