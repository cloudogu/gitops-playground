package com.cloudogu.gitops.config.scm.util

import com.cloudogu.gitops.config.Credentials

interface GitlabConfig {
    val url: String?
    val parentGroupId: String?
    val defaultVisibility: String?
    val gitOpsUsername: String?
    val credentials: Credentials?
}
