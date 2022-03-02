package com.cloudogu.gop.application.modules.argocd

import com.cloudogu.gop.application.clients.git.GitClient
import com.cloudogu.gop.application.clients.k8s.K8sClient
import com.cloudogu.gop.application.modules.GopModule
import com.cloudogu.gop.application.modules.argocd.config.MetricsConfig
import com.cloudogu.gop.application.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import org.springframework.security.crypto.bcrypt.BCrypt

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.SecureRandom
import java.security.spec.KeySpec

@Slf4j
class ArgoCDModule implements GopModule {

    private Map config

    ArgoCDModule(Map config) {
        this.config = config
    }

    @Override
    void run() {
        log.info("Running argocd module")
        GitClient git = new GitClient(config)

        configuringArgocdControlApp(git)
    }

    private void configuringArgocdControlApp(GitClient git) {
        String localGopSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        String absoluteControlAppTmpDir = "/tmp/repo_tmp_dir_for_control_app"

        log.debug("Cloning argocd control-app repo")
        git.clone(localGopSrcDir, scmmRepoTarget, absoluteControlAppTmpDir)

        MetricsConfig metricsConfig = new MetricsConfig(config, absoluteControlAppTmpDir)
        metricsConfig.metricsConfigurationInRepo()

        log.debug("Pushing configured argocd control-app repo")
        git.commitAndPush(scmmRepoTarget, absoluteControlAppTmpDir)
    }
}
