package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.local.GitRepo

interface GitProvider {

    Credentials getCredentials()

    void init()

    String getUrl()

    GitRepo getRepo(String target)

    void createRepo(String target, String description)

    Boolean isInternal()
}