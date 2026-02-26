package com.cloudogu.gitops.git.providers.scmmanager.api

class Repository {
	final String name
	final String namespace
	final String type
	final String contact
	final String description

	Repository(String namespace, String name, String description = null, String contact = null, String type = 'git') {
		this.namespace = namespace
		this.name = name
		this.type = type
		this.contact = contact
		this.description = description
	}

	String getFullRepoName() {
		return "${namespace}/${name}"
	}

	@Override
	String toString() {
		"Repository{name='$name', namespace='$namespace', type='$type', contact='$contact', description='$description'}"
	}
}