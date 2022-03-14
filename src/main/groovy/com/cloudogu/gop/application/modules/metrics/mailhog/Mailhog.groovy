package com.cloudogu.gop.application.modules.metrics.mailhog


import com.cloudogu.gop.application.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import org.springframework.security.crypto.bcrypt.BCrypt

@Slf4j
class Mailhog {

    private boolean remoteCluster
    private String username
    private String password
    private String mailhogUsername
    private String mailhogPassword
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils


    Mailhog(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.remoteCluster = config.application["remote"]
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.mailhogUsername = config.mailhog["username"]
        this.mailhogPassword = config.mailhog["password"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils

    }

    void configure() {
        log.debug("Configuring mailhog")
        String mailhogYaml = "applications/application-mailhog-helm.yaml"

        if (!remoteCluster) {
            log.debug("Setting mailhog service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, mailhogYaml, "LoadBalancer", "NodePort")
        }

        if (username != mailhogUsername || password != mailhogPassword) {
            log.debug("Setting new mailhog credentials")
            String bcryptMailhogPassword = BCrypt.hashpw(mailhogPassword, BCrypt.gensalt(4))
            String from = "fileContents: \"admin:\$2a\$04\$bM4G0jXB7m7mSv4UT8IuIe3.Bj6i6e2A13ryA0ln.hpyX7NeGQyG.\""
            String to = "fileContents: \"$mailhogUsername:$bcryptMailhogPassword\""
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, mailhogYaml, from, to)
        } else {
            log.debug("Not setting mailhog credentials since none were set. Using default application credentials")
        }
    }
}
