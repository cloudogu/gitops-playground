package com.cloudogu.gitops.core.modules.metrics.argocd

import com.cloudogu.gitops.core.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCDNotifications {

    private String argocdUrl
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils

    ArgoCDNotifications(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.argocdUrl = config.modules["argocd"]["url"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
    }

    void configure() {
        String argoNotificationsYaml = "applications/application-argocd-notifications.yaml"

        if (!argocdUrl) {
            log.debug("Setting argocd url")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, argoNotificationsYaml, 
                    "argocdUrl: http://localhost:9092", "argocdUrl: $argocdUrl")
        }
    }
}
