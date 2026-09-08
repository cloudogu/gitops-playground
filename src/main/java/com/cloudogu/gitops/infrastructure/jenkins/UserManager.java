package com.cloudogu.gitops.infrastructure.jenkins;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class UserManager {

	private final JenkinsApiClient apiClient;

	public void createUser(String username, String password) {
		log.debug("Add user {} to jenkins", username);

		String script = """
			def realm = Jenkins.getInstance().getSecurityRealm()
			def user = realm.createAccount('%USERNAME%', '%PASSWORD%')
			
			print(user)
			""";

		script = script.replace("%USERNAME%", escapeString(username)).replace("%PASSWORD%", escapeString(password));

		String result = apiClient.runScript(script);

		if (!username.equals(result)) {
			throw new IllegalStateException("Error when creating user: " + result);
		}
	}

	public void grantPermission(String username, Permissions permission) {
		if (!isUsingMatrixBasedPermissions()) {
			log.debug("Is not using matrix based permission. Does not need to add permission.");
			return;
		}

		log.debug("Grant user {} permission {}", username, permission);

		String script = """
			import org.jenkinsci.plugins.matrixauth.PermissionEntry
			import org.jenkinsci.plugins.matrixauth.AuthorizationType
			
			def permissions = Jenkins.getInstance().getAuthorizationStrategy().getGrantedPermissionEntries()
			permissions.computeIfAbsent(%PERMISSION%) {
			new HashSet<>()
			}
			print(permissions[%PERMISSION%].add(new PermissionEntry(AuthorizationType.USER, '%USERNAME%')))
			""";

		script = script.replace("%PERMISSION%", permission.toJenkinsPermissionEnum())
					   .replace("%USERNAME%", escapeString(username));

		String result = apiClient.runScript(script);

		if (!"true".equals(result) && !"false".equals(result)) {
			// Both are valid return values for Set.add(). true == was already in set, false == was not
			// already in set
			throw new IllegalStateException("Failed to add permission " + permission + " to " + username + ": " + result);
		}
	}

	public boolean isUsingMatrixBasedPermissions() {
		String result = apiClient.runScript("print(Jenkins.getInstance().getAuthorizationStrategy().class)");

		if (!result.startsWith("class ")) {
			throw new IllegalStateException("Error when trying to determine authorization strategy: " + result);
		}

		return "class hudson.security.GlobalMatrixAuthorizationStrategy".equals(result) || "class hudson.security.ProjectMatrixAuthorizationStrategy".equals(
			result);
	}

	public boolean isUsingSecurityRealmWithoutLocalUserCreation() {
		String result = apiClient.runScript("print(Jenkins.getInstance().getSecurityRealm().class)");

		if (!result.startsWith("class ")) {
			throw new IllegalStateException("Error when trying to determine security realm: " + result);
		}

		return List.of(
					   "class org.jenkinsci.plugins.cas.CasSecurityRealm",
					   "class org.jenkinsci.plugins.oic.OicSecurityRealm"
				   )
				   .contains(result);
	}

	private static String escapeString(String str) {
		if (str.contains("\\")) {
			// We don't want to get in trouble with escaping,
			// e.g. `foo\'foo` => `foo\\'foo`. Now we would have a backslash followed by an unescaped
			// quote.
			throw new IllegalArgumentException("Backslashes within the escaped variables are forbidden.");
		}

		return str.replace("'", "\\'");
	}

	public enum Permissions {
		METRICS_VIEW("jenkins.metrics.api.Metrics.VIEW");

		private final String value;

		Permissions(String value) {
			this.value = value;
		}

		public String toJenkinsPermissionEnum() {
			return value;
		}
	}
}
