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
import org.springframework.security.crypto.bcrypt.BCrypt


@Slf4j
@Singleton
@Order(200)
class Mailhog extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/mailhog/templates/mailhog-helm-values.ftl.yaml"

    String namespace = "${config.application.namePrefix}monitoring"
    Config config
    K8sClient k8sClient

    private String username
    private String password

    Mailhog(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils,
            GitHandler gitHandler
    ) {
        this.deployer = deployer
        this.config = config
        this.password = this.config.application.password
        this.k8sClient = k8sClient
        this.fileSystemUtils = fileSystemUtils
        this.airGappedUtils = airGappedUtils
        this.gitHandler = gitHandler
    }


    @Override
    boolean isEnabled() {
        return config.features.mail.mailhog
    }

    @Override
    void enable() {
        String bcryptMailhogPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))

        Map configParameters = [
                mail         : [
                        // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                        host: config.features.mail.mailhogUrl ? new URL(config.features.mail.mailhogUrl).host : "",
                ],
                passwordCrypt: bcryptMailhogPassword,
                config       : config,
                // Allow for using static classes inside the templates
                statics      : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ]

        def helmConfig = config.features.mail.helm
        deployHelmChart('mailhog', 'mailhog', namespace, helmConfig, HELM_VALUES_PATH, configParameters, config)
    }
}