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
@Order(400)
class ExternalSecretsOperator extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/external-secrets/templates/values.ftl.yaml"

    String namespace = "${config.application.namePrefix}secrets"
    Config config
    K8sClient k8sClient

    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils
    private GitHandler gitHandler

    ExternalSecretsOperator(
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
        this.gitHandler=gitHandler
    }

    @Override
    boolean isEnabled() {
        return config.features.secrets.active
    }

    @Override
    void enable() {

        Map configParameters = [
                config : config,
                // Allow for using static classes inside the templates
                statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ]

        def helmConfig = config.features.secrets.externalSecrets.helm
        deployHelmChart('external-secrets-operator', 'external-secrets', namespace, helmConfig, HELM_VALUES_PATH, configParameters, config, deployer, airGappedUtils, gitHandler)
    }
}