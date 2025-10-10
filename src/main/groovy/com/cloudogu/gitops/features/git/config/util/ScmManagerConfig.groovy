package com.cloudogu.gitops.features.git.config.util

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials


interface ScmManagerConfig {
    boolean isInternal()
    String url
    public String username = Config.DEFAULT_ADMIN_USER
    public String password = Config.DEFAULT_ADMIN_PW
    String namespace
    String ingress
    Config.HelmConfigWithValues helm
    String rootPath
    Boolean insecure
    String gitOpsUsername

    Credentials getCredentials()
}