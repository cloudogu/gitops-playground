package com.cloudogu.gitops.config

import com.cloudogu.gitops.Feature
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(40)
class ConfigValidator extends Feature {

    ConfigValidator() {
        log.debug("Doing common pre/post config validation...")
    }

    @Override
    void preConfigValidation(Config configToSet) {
        validateScmmAndJenkinsAreBothSet(configToSet)
        validateMirrorReposHelmChartFolderSet(configToSet)
    }

    private void validateScmmAndJenkinsAreBothSet(Config configToSet) {
        if (configToSet.jenkins.active &&
                (configToSet.scmm.url && !configToSet.jenkins.url ||
                        !configToSet.scmm.url && configToSet.jenkins.url)) {
            throw new RuntimeException('When setting jenkins URL, scmm URL must also be set and the other way round')
        }
    }

    private void validateMirrorReposHelmChartFolderSet(Config configToSet) {
        if (configToSet.application.mirrorRepos && !configToSet.application.localHelmChartFolder) {
            // This should only happen when run outside the image, i.e. during development
            throw new RuntimeException("Missing config for localHelmChartFolder.\n" +
                    "Either run inside the official container image or setting env var " +
                    "LOCAL_HELM_CHART_FOLDER='charts' after running 'scripts/downloadHelmCharts.sh' from the repo")
        }
    }

    @Override
    boolean isEnabled() {
        return false
    }
}
