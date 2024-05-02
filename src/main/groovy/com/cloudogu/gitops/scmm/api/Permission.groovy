package com.cloudogu.gitops.scmm.api

class Permission {
    final String name
    final Role role
    final List<String> verbs
    final boolean groupPermission

    Permission(String name, Role role, boolean groupPermission = false, List<String> verbs = []) {
        this.name = name
        this.role = role
        this.verbs = verbs
        this.groupPermission = groupPermission
    }

    @Override
    String toString() {
        "Permission{name='$name', role=$role, verbs=$verbs, groupPermission=$groupPermission}"
    }

    enum Role {
        READ, WRITE,OWNER
    }
}