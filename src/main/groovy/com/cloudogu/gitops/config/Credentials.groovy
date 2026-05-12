package com.cloudogu.gitops.config

import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION

import groovy.transform.ToString

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@ToString
class Credentials {

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	String username
	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	@JsonIgnore
	String password
	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	String secretNamespace
	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	String secretName
	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	String usernameKey = 'username'
	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	String passwordKey = 'password'

	Credentials() {}

	Credentials(String username, String password, String secretName = '', String secretNamespace = '', String usernameKey = "username", String passwordKey = 'password') {
		this.username = username
		this.password = password
		this.secretNamespace = secretNamespace
		this.secretName = secretName
		this.usernameKey = usernameKey
		this.passwordKey = passwordKey
	}

	Credentials(Credentials unsafeCredentials) {
		this.secretNamespace = unsafeCredentials.secretNamespace
		this.secretName = unsafeCredentials.secretName
		this.usernameKey = unsafeCredentials.usernameKey
		this.passwordKey = unsafeCredentials.passwordKey
	}
}