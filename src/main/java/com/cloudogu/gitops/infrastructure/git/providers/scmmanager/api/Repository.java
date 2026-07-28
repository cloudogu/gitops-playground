package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import lombok.Getter;
import lombok.ToString;

/** Request payload describing an SCM-Manager repository to be created via {@link RepositoryApi}. */
@Getter
@ToString
public class Repository {
private final String name;
private final String namespace;
private final String type;
private final String contact;
private final String description;

/**
* Creates a git repository payload without description and contact.
*
* @param namespace SCM-Manager namespace of the repository
* @param name name of the repository
*/
public Repository(String namespace, String name) {
	this(namespace, name, null, null, "git");
}

/**
* Creates a git repository payload without contact.
*
* @param namespace SCM-Manager namespace of the repository
* @param name name of the repository
* @param description free-text description of the repository
*/
public Repository(String namespace, String name, String description) {
	this(namespace, name, description, null, "git");
}

/**
* Creates a git repository payload.
*
* @param namespace SCM-Manager namespace of the repository
* @param name name of the repository
* @param description free-text description of the repository
* @param contact contact mail address shown for the repository
*/
public Repository(String namespace, String name, String description, String contact) {
	this(namespace, name, description, contact, "git");
}

/**
* Creates a repository payload.
*
* @param namespace SCM-Manager namespace of the repository
* @param name name of the repository
* @param description free-text description of the repository
* @param contact contact mail address shown for the repository
* @param type repository type; defaults to {@code git} when {@code null}
*/
public Repository(
	String namespace, String name, String description, String contact, String type) {
	this.namespace = namespace;
	this.name = name;
	this.type = type != null ? type : "git";
	this.contact = contact;
	this.description = description;
}

public String getFullRepoName() {
	return namespace + "/" + name;
}
}
