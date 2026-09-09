package com.cloudogu.gitops.application.credentials;

import com.cloudogu.gitops.config.Credentials;

public record CredentialsReference(
	String secretName,
	String secretNamespace,
	String usernameKey,
	String passwordKey
) {

	public static CredentialsReference from(Credentials credentials) {
		if (credentials == null) {
			return null;
		}

		return new CredentialsReference(
			credentials.getSecretName(),
			credentials.getSecretNamespace(),
			credentials.getUsernameKey(),
			credentials.getPasswordKey()
		);
	}
}
