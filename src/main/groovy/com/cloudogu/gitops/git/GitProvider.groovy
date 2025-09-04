package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Credentials

interface GitProvider {

    Credentials getCredentials()
    void init()
    String getUrl()
    GitRepo getRepo(String target)
    void createRepo(String target,String description)
}