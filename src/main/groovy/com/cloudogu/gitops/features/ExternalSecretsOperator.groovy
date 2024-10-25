package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.TemplatingEngine
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
@Order(400)
class ExternalSecretsOperator extends Feature implements FeatureWithImage {
    
    static final String HELM_VALUES_PATH = 'applications/cluster-resources/secrets/external-secrets/values.ftl.yaml'
    
    String namespace = 'secrets'
    Config config
    K8sClient k8sClient
    
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils

    ExternalSecretsOperator(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
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

        def helmConfig = config.features.secrets.externalSecrets.helm as Config.HelmConfig
        def helmValuesYaml = templateToMap(HELM_VALUES_PATH, [
                        config: config,
                        // Allow for using static classes inside the templates
                        statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
                ])

        def tmpHelmValues = fileSystemUtils.createTempFile()
        fileSystemUtils.writeYaml(helmValuesYaml, tmpHelmValues.toFile())

        if (config.application.mirrorRepos) {
            log.debug("Mirroring repos: Deploying externalSecretsOperator from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config.features.secrets.externalSecrets.helm as Config.HelmConfig)

            String externalSecretsVersion =
                    new YamlSlurper().parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    "external-secrets",
                    '.',
                    externalSecretsVersion,
                    namespace,
                    'external-secrets',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT
            )
        } else {
            deployer.deployFeature(
                helmConfig.repoURL,
                "externalsecretsoperator",
                helmConfig.chart,
                helmConfig.version,
                    namespace,
                'external-secrets',
                tmpHelmValues
            )
        }
    }
    private URI getScmmUri() {
        if (config.scmm.internal) {
            new URI('http://scmm-scm-manager.default.svc.cluster.local/scm')
        } else {
            new URI("${config.scmm.url}/scm")
        }
    }

    Map templateToMap(String filePath, Map parameters) {
        def hydratedString = new TemplatingEngine().template(new File(filePath), parameters)
        
        if (hydratedString.trim().isEmpty()) {
            // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
            return [:]
        } 
        return new YamlSlurper().parseText(hydratedString) as Map
    }
}
