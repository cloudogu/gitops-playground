package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCDNotifications extends Feature {

    private Map config
    private String argocdUrl
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils

    ArgoCDNotifications(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.config = config
        this.argocdUrl = config.features["argocd"]["url"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        config.features["argocd"]["active"]
    }
    
    @Override
    void enable() {
        String argoNotificationsYaml = "applications/system/application-argocd-notifications.yaml"

        if (argocdUrl) {
            log.debug("Setting argocd url")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, argoNotificationsYaml, 
                    "argocdUrl: http://localhost:9092", "argocdUrl: $argocdUrl")
        }
    }

}
