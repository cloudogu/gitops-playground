package com.cloudogu.gitops.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION;

@Getter
@Setter
@ToString(exclude = "password")
@NoArgsConstructor
public class Credentials {

	private static final String DEFAULT_USERNAME_KEY = "username";
	private static final String DEFAULT_PASSWORD_KEY = "password";

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	private String username;

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	@JsonIgnore
	private String password;

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	private String secretNamespace;

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	private String secretName;

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	private String usernameKey = DEFAULT_USERNAME_KEY;

	@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
	private String passwordKey = DEFAULT_PASSWORD_KEY;

	public Credentials(String username, String password) {
		this(username, password, "", "", DEFAULT_USERNAME_KEY, DEFAULT_PASSWORD_KEY);
	}

	public Credentials(String username, String password, String secretName) {
		this(username, password, secretName, "", DEFAULT_USERNAME_KEY, DEFAULT_PASSWORD_KEY);
	}

	public Credentials(String username, String password, String secretName, String secretNamespace) {
		this(username, password, secretName, secretNamespace, DEFAULT_USERNAME_KEY, DEFAULT_PASSWORD_KEY);
	}

	public Credentials(
		String username,
		String password,
		String secretName,
		String secretNamespace,
		String usernameKey) {
		this(username, password, secretName, secretNamespace, usernameKey, DEFAULT_PASSWORD_KEY);
	}

	public Credentials(
		String username,
		String password,
		String secretName,
		String secretNamespace,
		String usernameKey,
		String passwordKey) {
		this.username = username;
		this.password = password;
		this.secretNamespace = secretNamespace;
		this.secretName = secretName;
		this.usernameKey = usernameKey;
		this.passwordKey = passwordKey;
	}

	public Credentials(Credentials unsafeCredentials) {
		if (unsafeCredentials != null) {
			this.username = unsafeCredentials.username;
			this.password = unsafeCredentials.password;
			this.secretNamespace = unsafeCredentials.secretNamespace;
			this.secretName = unsafeCredentials.secretName;
			this.usernameKey = unsafeCredentials.usernameKey;
			this.passwordKey = unsafeCredentials.passwordKey;
		}
	}

	/**
	 * ensures that secretName and secretNamespace are not null
	 *
	 * @return true, if User sets credentials with secretName and secretNamespace otherwise false.
	 */
	@JsonIgnore
	public boolean isUsed() {
		return secretName != null && secretNamespace != null;
	}
}
