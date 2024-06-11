package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.DockerImageParser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
@Order(400)
class ExternalSecretsOperator extends Feature {
    private Map config
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private K8sClient k8sClient
    private AirGappedUtils airGappedUtils
    static final String HELM_VALUES_PATH = 'applications/cluster-resources/secrets/external-secrets/values.ftl.yaml'

    ExternalSecretsOperator(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
    }

    @Override
    boolean isEnabled() {
        return config.features['secrets']['active']
    }

    @Override
    void enable() {
        def helmConfig = config['features']['secrets']['externalSecrets']['helm']
        def helmValuesYaml = new YamlSlurper().parseText(
                new TemplatingEngine().template(new File(HELM_VALUES_PATH), [
                        podResources: config.application['podResources'],
                ])) as Map

        if (helmConfig['image']) {
            log.debug("Setting custom ESO image as requested for external-secrets-operator")
            def image = DockerImageParser.parse(helmConfig['image'] as String)
            MapUtils.deepMerge([
                    image: [
                            repository: image.getRegistryAndRepositoryAsString(),
                            tag       : image.tag
                    ]
            ], helmValuesYaml)
        }
        if (helmConfig['certControllerImage']) {
            log.debug("Setting custom cert-controller image as requested for external-secrets-operator")
            def image = DockerImageParser.parse(helmConfig['certControllerImage'] as String)
            MapUtils.deepMerge([
                    certController: [
                            image: [
                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }
        if (helmConfig['webhookImage']) {
            log.debug("Setting custom webhook image as requested for external-secrets-operator")
            def image = DockerImageParser.parse(helmConfig['webhookImage'] as String)
            MapUtils.deepMerge([
                    webhook: [
                            image: [
                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }

        def tmpHelmValues = fileSystemUtils.createTempFile()
        fileSystemUtils.writeYaml(helmValuesYaml, tmpHelmValues.toFile())

        if (config.application['mirrorRepos']) {
            log.debug("Mirroring repos: Deploying externalSecretsOperator from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config['features']['secrets']['externalSecrets']['helm'] as Map)

            String externalSecretsVersion =
                    new YamlSlurper().parse(Path.of("${config.application['localHelmChartFolder']}/${helmConfig['chart']}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    "external-secrets",
                    '.',
                    externalSecretsVersion,
                    'secrets',
                    'external-secrets',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT
            )
        } else {
            deployer.deployFeature(
                helmConfig['repoURL'] as String,
                "externalsecretsoperator",
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'secrets',
                'external-secrets',
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
