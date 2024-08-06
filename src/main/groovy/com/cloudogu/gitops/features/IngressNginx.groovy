package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
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
@Order(150)
class IngressNginx extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/ingress-nginx-helm-values.ftl.yaml"

    private Map config
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils
    private K8sClient k8sClient

    IngressNginx(
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
        return config.features['ingressNginx']['active']
    }

    @Override
    void enable() {

        def templatedMap = new YamlSlurper().parseText(
                new TemplatingEngine().template(new File(HELM_VALUES_PATH),
                    [
                            podResources: config.application['podResources'],
                    ])) as Map

        def valuesFromConfig = config['features']['ingressNginx']['helm']['values'] as Map

        def mergedMap = MapUtils.deepMerge(valuesFromConfig, templatedMap)

        def tmpHelmValues = fileSystemUtils.createTempFile()
        // Note that YAML builder seems to use double quotes to escape strings. So for example:
        // This:     log-format-upstream: '..."$request"...'
        // Becomes:  log-format-upstream: "...\"$request\"..."
        // Harder to read but same payload. Not sure if we can do something about it.
        fileSystemUtils.writeYaml(mergedMap, tmpHelmValues.toFile())

        def helmConfig = config['features']['ingressNginx']['helm']

        if (config.application['mirrorRepos']) {
            log.debug("Mirroring repos: Deploying IngressNginx from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config['features']['ingressNginx']['helm'] as Map)

            String ingressNginxVersion =
                    new YamlSlurper().parse(Path.of("${config.application['localHelmChartFolder']}/${helmConfig['chart']}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'ingress-nginx',
                    '.',
                    ingressNginxVersion,
                    'ingress-nginx',
                    'ingress-nginx',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT)
        } else {
            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'ingress-nginx',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    'ingress-nginx',
                    'ingress-nginx',
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
