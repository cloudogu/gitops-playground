package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.kubernetes.api.K8sClient
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(160)
class CertManager extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/cert-manager/templates/certManager-helm-values.ftl.yaml"

    final K8sClient k8sClient
    final Config config
    final String namespace = "${config.application.namePrefix}cert-manager"

    CertManager(
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
        return config.features.certManager.active
    }

    @Override
    void enable() {

        Map configParameters = [
                        config : config,
                        // Allow for using static classes inside the templates
                        statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build()
                                .getStaticModels(),
                ]

        def helmConfig = config.features.certManager.helm
        deployHelmChart('cert-manager', 'cert-manager', namespace, helmConfig, HELM_VALUES_PATH, configParameters, config)
    }
}