package com.cloudogu.gitops.features.git.config.util

import com.cloudogu.gitops.config.Credentials

interface GitlabConfig {
    String url
    Credentials credentials
    String parentGroupId
    String defaultVisibility
    Boolean autoCreateGroups
    String gitOpsUsername
}