package com.cloudogu.gitops.scmm.api

class ScmUser {
    String name
    String displayName
    String mail
    boolean external = false
    String password
    boolean active = true
    Map _links = [:]
}