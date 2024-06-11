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
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
@Singleton
@Order(200)
class Mailhog extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/mailhog-helm-values.ftl.yaml"

    private Map config
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils
    private K8sClient k8sClient

    Mailhog(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.username = this.config.application["username"]
        this.password = this.config.application["password"]
        this.k8sClient = k8sClient
        this.fileSystemUtils = fileSystemUtils
        this.airGappedUtils = airGappedUtils
    }


    @Override
    boolean isEnabled() {
        return config.features['mail']['mailhog']
    }

    @Override
    void enable() {
        String bcryptMailhogPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [
                mail         : [
                        // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                        host: config.features['mail']['mailhogUrl'] ? new URL(config.features['mail']['mailhogUrl'] as String).host : "",
                ],
                image        : config['features']['mail']['helm']['image'] as String,
                isRemote     : config.application['remote'],
                username     : username,
                passwordCrypt: bcryptMailhogPassword,
                podResources: config.application['podResources'],
        ]).toPath()
        Map helmValuesYaml = fileSystemUtils.readYaml(tmpHelmValues)
        def helmConfig = config['features']['mail']['helm']

        if (config.application['mirrorRepos']) {
            log.debug("Mirroring repos: Deploying mailhog from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config['features']['mail']['helm'] as Map)

            String mailhogVersion =
                    new YamlSlurper().parse(Path.of("${config.application['localHelmChartFolder']}/${helmConfig['chart']}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'mailhog',
                    '.',
                    mailhogVersion,
                    'monitoring',
                    'mailhog',
                    tmpHelmValues, DeploymentStrategy.RepoType.GIT)
        } else {
            fileSystemUtils.writeYaml(helmValuesYaml, tmpHelmValues.toFile())

            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'mailhog',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    'monitoring',
                    'mailhog',
                    tmpHelmValues)
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
