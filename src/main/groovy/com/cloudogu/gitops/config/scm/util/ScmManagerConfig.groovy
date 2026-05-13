package com.cloudogu.gitops.config.scm.util

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
    String getGitOpsUsername()

    Credentials getCredentials()
}
