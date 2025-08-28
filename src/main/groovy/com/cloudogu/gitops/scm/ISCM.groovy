package com.cloudogu.gitops.scm

import com.cloudogu.gitops.config.Credentials

interface ISCM {

    Credentials getCredentials()
    void init()
    String getUrl()
}