package com.cloudogu.gitops.git.scmm.api

class ScmUser {
    String name
    String displayName
    String mail
    boolean external = false
    String password
    boolean active = true
    Map _links = [:]
}