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
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
@Singleton
@Order(200)
class Mailhog extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/mailhog-helm-values.ftl.yaml"

    String namespace = 'monitoring'
    Config config
    K8sClient k8sClient
    
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils

    Mailhog(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils
    ) {
        this.deployer = deployer
        this.config = config
        this.username = this.config.application.username
        this.password = this.config.application.password
        this.k8sClient = k8sClient
        this.fileSystemUtils = fileSystemUtils
        this.airGappedUtils = airGappedUtils
    }


    @Override
    boolean isEnabled() {
        return config.features.mail.mailhog
    }

    @Override
    void enable() {
        
        String bcryptMailhogPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [
                mail         : [
                        // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                        host: config.features.mail.mailhogUrl ? new URL(config.features.mail.mailhogUrl ).host : "",
                ],
                isRemote     : config.application.remote,
                username     : username,
                passwordCrypt: bcryptMailhogPassword,
                podResources: config.application.podResources,
                config : config,
                // Allow for using static classes inside the templates
                statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ]).toPath()

        def helmConfig = config.features.mail.helm

        if (config.application.mirrorRepos) {
            log.debug("Mirroring repos: Deploying mailhog from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config.features.mail.helm as Config.HelmConfig)

            String mailhogVersion =
                    new YamlSlurper().parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'mailhog',
                    '.',
                    mailhogVersion,
                    namespace,
                    'mailhog',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT)
        } else {
            deployer.deployFeature(
                    helmConfig.repoURL ,
                    'mailhog',
                    helmConfig.chart ,
                    helmConfig.version ,
                    namespace,
                    'mailhog',
                    tmpHelmValues)
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
