package com.cloudogu.gitops.git.providers.scmmanager.api

class ScmManagerUser {
    String name
    String displayName
    String mail
    boolean external = false
    String password
    boolean active = true
    Map _links = [:]
}