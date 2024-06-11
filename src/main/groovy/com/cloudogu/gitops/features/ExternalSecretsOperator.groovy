package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.DockerImageParser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
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
        def helmValuesPath = fileSystemUtils.copyToTempDir('applications/cluster-resources/secrets/external-secrets/values.yaml')
        def helmValuesYaml = fileSystemUtils.readYaml(helmValuesPath)
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
        fileSystemUtils.writeYaml(helmValuesYaml, helmValuesPath.toFile())

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
                    helmValuesPath, DeploymentStrategy.RepoType.GIT
            )
        } else {
            deployer.deployFeature(
                helmConfig['repoURL'] as String,
                "externalsecretsoperator",
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'secrets',
                'external-secrets',
                helmValuesPath
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
