package com.cloudogu.gitops.scm.config.util

import com.cloudogu.gitops.config.Credentials

interface GitlabConfig {
    String url
    Credentials credentials
    String parentGroup
}