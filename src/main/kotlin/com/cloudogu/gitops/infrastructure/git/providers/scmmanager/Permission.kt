package com.cloudogu.gitops.infrastructure.git.providers.scmmanager

class Permission @JvmOverloads constructor(
    val name: String,
    val role: Role,
    val groupPermission: Boolean = false,
    val verbs: List<String> = emptyList()
) {
    override fun toString(): String {
        return "Permission{name='$name', role=$role, verbs=$verbs, groupPermission=$groupPermission}"
    }

    enum class Role {
        READ, WRITE, OWNER
    }
}
