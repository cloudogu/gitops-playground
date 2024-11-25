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
@Order(160)
class CertManager extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/certManager-helm-values.ftl.yaml"

    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils
    final K8sClient k8sClient
    final Config config
    final String namespace = "cert-manager"

    CertManager(
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
        return config.features.certManager.active
    }

    @Override
    void enable() {

        def templatedMap = new YamlSlurper().parseText(
                new TemplatingEngine().template(new File(HELM_VALUES_PATH),
                        [config : config,
                         // Allow for using static classes inside the templates
                         statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build()
                                 .getStaticModels(),
                        ])) as Map


        def valuesFromConfig = config.features.certManager.helm.values

        def mergedMap = MapUtils.deepMerge(valuesFromConfig, templatedMap)

        def tmpHelmValues = fileSystemUtils.createTempFile()
        fileSystemUtils.writeYaml(mergedMap, tmpHelmValues.toFile())

        def helmConfig = config.features.certManager.helm
        if (config.application.mirrorRepos) {
            log.debug("Mirroring repos: Deploying certManager from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config.features.certManager.helm)

            String certManagerVersion =
                    new YamlSlurper().parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'cert-manager',
                    '.',
                    certManagerVersion,
                    'cert-manager',
                    'cert-manager',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT)
        } else {
            deployer.deployFeature(
                    helmConfig.repoURL,
                    'cert-manager',
                    helmConfig.chart,
                    helmConfig.version,
                    'cert-manager',
                    'cert-manager',
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

}
