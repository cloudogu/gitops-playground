package com.cloudogu.gitops.features.git.config.util

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials


interface ScmmConfig {
    Boolean internal
    String url  //TODO maybe rename to externalUrl?
    public String username = Config.DEFAULT_ADMIN_USER
    public String password = Config.DEFAULT_ADMIN_PW
    String namespace ='scm-manager'
    String ingress
    Config.HelmConfigWithValues helm
    Credentials getCredentials()
    String rootPath
}