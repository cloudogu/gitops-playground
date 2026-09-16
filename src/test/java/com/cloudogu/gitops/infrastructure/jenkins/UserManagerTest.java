package com.cloudogu.gitops.infrastructure.jenkins;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserManagerTest {

	@Test
	void createsUserSuccessfully() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("the-user");

		new UserManager(client).createUser("the-user", "hunter2");
		verify(client).runScript(anyString());
	}

	@Test
	void createsUserWithQuotesSuccessfully() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("the-'user");

		new UserManager(client).createUser("the-'user", "code''injection");
		verify(client).runScript("""
			def realm = Jenkins.getInstance().getSecurityRealm()
			def user = realm.createAccount('the-\\'user', 'code\\'\\'injection')
			
			print(user)
			""");
	}

	@Test
	void throwsWhenBackslashesArePassed() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);

		assertThrows(
			IllegalArgumentException.class,
			() -> new UserManager(client).createUser("the-\\'user", "hunter2")
		);
	}

	@Test
	void throwsWhenThereWasAnError() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString()))
			.thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]");

		assertThrows(
			RuntimeException.class,
			() -> new UserManager(client).createUser("the-user", "hunter2")
		);
	}

	@Test
	void grantsPermissionForUser() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("true");
		when(client.runScript("print(Jenkins.getInstance().getAuthorizationStrategy().class)"))
			.thenReturn("class hudson.security.GlobalMatrixAuthorizationStrategy");

		new UserManager(client).grantPermission("the-'user", UserManager.Permissions.METRICS_VIEW);

		verify(client).runScript("print(Jenkins.getInstance().getAuthorizationStrategy().class)");
		verify(client).runScript("""
			import org.jenkinsci.plugins.matrixauth.PermissionEntry
			import org.jenkinsci.plugins.matrixauth.AuthorizationType
			
			def permissions = Jenkins.getInstance().getAuthorizationStrategy().getGrantedPermissionEntries()
			permissions.computeIfAbsent(jenkins.metrics.api.Metrics.VIEW) {
			new HashSet<>()
			}
			print(permissions[jenkins.metrics.api.Metrics.VIEW].add(new PermissionEntry(AuthorizationType.USER, 'the-\\'user')))
			""");
	}

	@Test
	void throwsWhenGrantingPermissionFailed() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString()))
			.thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]");

		assertThrows(
			RuntimeException.class,
			() -> new UserManager(client).grantPermission("the-'user", UserManager.Permissions.METRICS_VIEW)
		);
	}

	@Test
	void checksWhetherMatrixBasedAuthorizationIsEnabled() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("class hudson.security.GlobalMatrixAuthorizationStrategy");

		assertThat(new UserManager(client).isUsingMatrixBasedPermissions()).isTrue();
	}

	@Test
	void checksWhetherMatrixBasedAuthorizationIsDisabled() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn(
			"class hudson.security.FullControlOnceLoggedInAuthorizationStrategy");

		assertThat(new UserManager(client).isUsingMatrixBasedPermissions()).isFalse();
	}

	@Test
	void checksWhetherSecurityRealmWithoutLocalUserCreationIsUsedForCas() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("class org.jenkinsci.plugins.cas.CasSecurityRealm");

		assertThat(new UserManager(client).isUsingSecurityRealmWithoutLocalUserCreation()).isTrue();
	}

	@Test
	void checksWhetherSecurityRealmWithoutLocalUserCreationIsUsedForOic() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("class org.jenkinsci.plugins.oic.OicSecurityRealm");

		assertThat(new UserManager(client).isUsingSecurityRealmWithoutLocalUserCreation()).isTrue();
	}

	@Test
	void checksWhetherLocalUserCreationIsSupported() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("class hudson.security.HudsonPrivateSecurityRealm");

		assertThat(new UserManager(client).isUsingSecurityRealmWithoutLocalUserCreation()).isFalse();
	}

	@Test
	void throwsWhenDeterminingSecurityRealmErrors() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString()))
			.thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]");

		assertThrows(
			RuntimeException.class,
			() -> new UserManager(client).isUsingSecurityRealmWithoutLocalUserCreation()
		);
	}
}
