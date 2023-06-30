package com.cloudogu.gitops.jenkins

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Singleton
@Slf4j
class UserManager {
    private ApiClient apiClient

    UserManager(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void createUser(String username, String password) {
        def result = apiClient.runScript("""
            def realm = Jenkins.getInstance().getSecurityRealm()
            def user = realm.createAccount('${escapeString(username)}', '${escapeString(password)}')

            print(user)
        """)

        log.debug("Added user $username to jenkins")

        if (result != username) {
            throw new RuntimeException("Error when creating user: $result")
        }
    }

    void grantPermission(String username, Permissions permission) {
        def result = apiClient.runScript("""
            import org.jenkinsci.plugins.matrixauth.PermissionEntry
            import org.jenkinsci.plugins.matrixauth.AuthorizationType

            def permissions = Jenkins.getInstance().getAuthorizationStrategy().getGrantedPermissionEntries()
            permissions.computeIfAbsent(jenkins.metrics.api.Metrics.VIEW) {
              new HashSet<>()
            }
            print(permissions[$permission].add(new PermissionEntry(AuthorizationType.USER, '${escapeString(username)}')))
        """)

        log.debug("Granted user $username permission $permission")

        if (result !in ["true", "false"]) { // true == was already in set, false == was not already in set
            throw new RuntimeException("Failed to add permission $permission to $username: $result")
        }
    }

    boolean isUsingMatrixBasedPermissions() {
        def result = apiClient.runScript("print(Jenkins.getInstance().getAuthorizationStrategy().class)")

        return result == "class hudson.security.GlobalMatrixAuthorizationStrategy"
    }

    private String escapeString(String str) {
        if (str.contains("\\")) {
            // We don't want get in trouble with escaping,
            // e.g. `foo\'foo` => `foo\\'foo`. Now we would have a backslash followed by an unescaped quote.
            throw new IllegalArgumentException("Backslashes within the escaped variables are forbidden.")
        }

        return str.replace("'", "\\'")
    }

    enum Permissions {
        METRICS_VIEW("jenkins.metrics.api.Metrics.VIEW")

        private final String value
        Permissions(String value){
            this.value = value
        }

        @Override
        String toString() {
            return value
        }
    }
}
