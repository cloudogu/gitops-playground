package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.local.GitRepo
import org.eclipse.jgit.transport.CredentialsProvider

interface GitProvider {

    Credentials getCredentials()

    String getUrl()

    void createRepo(String target, String description)

    Boolean isInternal()

    boolean createRepository(String repoTarget, String description, boolean initialize)


    String computePushUrl(String repoTarget)

    Credentials pushAuth()

    //TODO implement
    void deleteRepository(String namespace, String repository, boolean prefixNamespace)

    //TODO implement
    void deleteUser(String name)

    //TODO implement
    void setDefaultBranch(String repoTarget, String branch)

    String getProtocol()
    String getHost() //TODO? can we maybe get this via helper and config?

}