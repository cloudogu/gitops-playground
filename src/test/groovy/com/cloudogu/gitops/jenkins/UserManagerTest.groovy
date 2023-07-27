package com.cloudogu.gitops.jenkins


import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class UserManagerTest {
    @Test
    void 'creates user successfully'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("the-user")

        new UserManager(client).createUser("the-user", "hunter2")
        verify(client).runScript(anyString())
    }

    @Test
    void 'creates user with quotes successfully'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("the-'user")

        new UserManager(client).createUser("the-'user", "code''injection")
        verify(client).runScript("""
            def realm = Jenkins.getInstance().getSecurityRealm()
            def user = realm.createAccount('the-\\'user', 'code\\'\\'injection')

            print(user)
        """)
    }

    @Test
    void 'throws when backslashes are passed'() {
        def client = mock(ApiClient)
        shouldFail(IllegalArgumentException) {
            new UserManager(client).createUser("the-\\'user", "hunter2")
        }
    }

    @Test
    void 'throws when there was an error'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]")

        shouldFail(RuntimeException) {
            new UserManager(client).createUser("the-user", "hunter2")
        }
    }

    @Test
    void 'grants permission for user'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("true")
        when(client.runScript("print(Jenkins.getInstance().getAuthorizationStrategy().class)")).thenReturn("class hudson.security.GlobalMatrixAuthorizationStrategy")

        new UserManager(client).grantPermission("the-'user", UserManager.Permissions.METRICS_VIEW)

        verify(client).runScript("""print(Jenkins.getInstance().getAuthorizationStrategy().class)""")
        verify(client).runScript("""
            import org.jenkinsci.plugins.matrixauth.PermissionEntry
            import org.jenkinsci.plugins.matrixauth.AuthorizationType

            def permissions = Jenkins.getInstance().getAuthorizationStrategy().getGrantedPermissionEntries()
            permissions.computeIfAbsent(jenkins.metrics.api.Metrics.VIEW) {
              new HashSet<>()
            }
            print(permissions[jenkins.metrics.api.Metrics.VIEW].add(new PermissionEntry(AuthorizationType.USER, 'the-\\'user')))
        """)
    }

    @Test
    void 'throws when granting permission failed'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]")

        shouldFail(RuntimeException) {
            new UserManager(client).grantPermission("the-'user", UserManager.Permissions.METRICS_VIEW)
        }
    }

    @Test
    void 'checks whether matrix based authorization is enabled'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("class hudson.security.GlobalMatrixAuthorizationStrategy")

        assertThat(new UserManager(client).isUsingMatrixBasedPermissions()).isTrue()
    }

    @Test
    void 'checks whether matrix based authorization is disabled'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("class hudson.security.FullControlOnceLoggedInAuthorizationStrategy")

        assertThat(new UserManager(client).isUsingMatrixBasedPermissions()).isFalse()
    }

    @Test
    void 'checks whether cas security realm is used'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("class org.jenkinsci.plugins.cas.CasSecurityRealm")

        assertThat(new UserManager(client).isUsingCasSecurityRealm()).isTrue()
    }

    @Test
    void 'checks whether cas security realm is not used'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("class hudson.security.HudsonPrivateSecurityRealm")

        assertThat(new UserManager(client).isUsingCasSecurityRealm()).isFalse()
    }

    @Test
    void 'throws when determining security realm errors'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]")

        shouldFail(RuntimeException) {
            new UserManager(client).isUsingCasSecurityRealm()
        }
    }
}
