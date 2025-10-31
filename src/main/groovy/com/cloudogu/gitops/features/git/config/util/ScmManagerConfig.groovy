package com.cloudogu.gitops.features.git.config.util

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials


interface ScmManagerConfig {
    Boolean getInternal()

    String getUrl()
    String getUsername()
    String getPassword()
    String getNamespace()
    String getIngress()
    Config.HelmConfigWithValues getHelm()
    String getRootPath()
    String getGitOpsUsername()

    Credentials getCredentials()
}