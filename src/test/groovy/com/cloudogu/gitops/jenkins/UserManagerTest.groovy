package com.cloudogu.gitops.jenkins

import org.junit.jupiter.api.Test
import org.mockito.Mockito

import static groovy.test.GroovyAssert.shouldFail
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

class UserManagerTest {
    @Test
    void 'creates user successfully'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("Result: the-user\n")

        new UserManager(client).createUser("the-user", "hunter2")
        verify(client).runScript(anyString())
    }

    @Test
    void 'creates user with quotes successfully'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("Result: the-'user\n")

        new UserManager(client).createUser("the-'user", "code''injection")
        verify(client).runScript("""
            def realm = Jenkins.getInstance().getSecurityRealm()
            def user = realm.createAccount('the-\\'user', 'code\\'\\'injection')

            return user
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
}
