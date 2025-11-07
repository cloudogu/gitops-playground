package com.cloudogu.gitops.features.git.config.util

import com.cloudogu.gitops.config.Credentials

interface GitlabConfig {
    String getUrl()
    String getParentGroupId()
    String getDefaultVisibility()
    String getGitOpsUsername()
    Credentials getCredentials()
}