package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Credentials

interface ISCM {

    Credentials getCredentials()
    void init()
    String getUrl()
    GitRepo getRepo(String target)
}