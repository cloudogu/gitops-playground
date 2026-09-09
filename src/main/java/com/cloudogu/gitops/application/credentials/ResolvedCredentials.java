package com.cloudogu.gitops.application.credentials;

public record ResolvedCredentials(String username, String password) {

	@Override
	public String toString() {
		return "ResolvedCredentials[username=" + username + ", password=<redacted>]";
	}
}
