package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
@Order(150)
class Ingress extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/ingress/templates/ingress-helm-values.ftl.yaml"

    String namespace = "${config.application.namePrefix}" + config.features.ingress.ingressNamespace
    Config config
    K8sClient k8sClient

    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils
    private GitHandler gitHandler

    Ingress(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
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
        return config.features.ingress.active
    }

    @Override
    void enable() {

        def templatedMap = templateToMap(HELM_VALUES_PATH, [
                config : config,
                // Allow for using static classes inside the templates
                statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ])
        def helmConfig = config.features.ingress.helm
        def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
        def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

        deployHelmChart('traefik', 'traefik', namespace, helmConfig, tempValuesPath, config, deployer, airGappedUtils, gitHandler)
    }
}