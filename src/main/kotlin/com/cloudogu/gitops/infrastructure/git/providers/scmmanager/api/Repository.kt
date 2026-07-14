package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api

class Repository @JvmOverloads constructor(
    val namespace: String,
    val name: String,
    val description: String? = null,
    val contact: String? = null,
    val type: String = "git"
) {
    fun getFullRepoName(): String {
        return "$namespace/$name"
    }

    override fun toString(): String {
        return "Repository{name='$name', namespace='$namespace', type='$type', contact='$contact', description='$description'}"
    }
}
