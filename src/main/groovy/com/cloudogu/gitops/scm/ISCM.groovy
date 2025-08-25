package com.cloudogu.gitops.scm

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.config.ScmSchema.ScmProviderType


interface ISCM {

    Credentials getCredentials()
    void init()
    ScmProviderType getScmProviderType()
    String getUrl()
}