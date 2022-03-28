package com.cloudogu.gitops.core.modules.metrics.argocd

import com.cloudogu.gitops.core.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import org.springframework.security.crypto.bcrypt.BCrypt

@Slf4j
class Mailhog {

    static final String MAILHOG_YAML_PATH = "applications/application-mailhog-helm.yaml"

    private boolean remoteCluster
    private String username
    private String password
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils

    Mailhog(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.remoteCluster = config.application["remote"]
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils

    }

    void configure() {
        log.debug("Configuring mailhog")

        if (!remoteCluster) {
            log.debug("Setting mailhog service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, MAILHOG_YAML_PATH, "LoadBalancer", "NodePort")
        }

        log.debug("Setting new mailhog credentials")
        String bcryptMailhogPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        String from = "fileContents: \"admin:\$2a\$04\$bM4G0jXB7m7mSv4UT8IuIe3.Bj6i6e2A13ryA0ln.hpyX7NeGQyG.\""
        String to = "fileContents: \"$username:$bcryptMailhogPassword\""
        fileSystemUtils.replaceFileContent(tmpGitRepoDir, MAILHOG_YAML_PATH, from, to)
    }
}
