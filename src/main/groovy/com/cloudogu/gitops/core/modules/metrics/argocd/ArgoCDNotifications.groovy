package com.cloudogu.gitops.core.modules.metrics.argocd


import groovy.util.logging.Slf4j

@Slf4j
class ArgoCDNotifications {

    private String argocdUrl
    private String tmpGitRepoDir
    private com.cloudogu.gitops.core.utils.FileSystemUtils fileSystemUtils

    ArgoCDNotifications(Map config, String tmpGitRepoDir, com.cloudogu.gitops.core.utils.FileSystemUtils fileSystemUtils = new com.cloudogu.gitops.core.utils.FileSystemUtils()) {
        this.argocdUrl = config.modules["argocd"]["url"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
    }

    void configure() {
        String argoNotificationsYaml = "applications/application-argocd-notifications.yaml"

        if (argocdUrl != null && argocdUrl != "") {
            log.debug("Setting argocd url")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, argoNotificationsYaml, "argocdUrl: http://localhost:9092", "argocdUrl: $argocdUrl")
        }
    }
}
