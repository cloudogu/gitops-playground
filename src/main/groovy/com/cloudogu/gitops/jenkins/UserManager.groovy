package com.cloudogu.gitops.jenkins

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton
import org.intellij.lang.annotations.Language

@Singleton
@Slf4j
class UserManager {
    private ApiClient apiClient

    UserManager(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void createUser(String username, String password) {
        log.debug("Add user $username to jenkins")
        
        @Language("Groovy")
        def script = """
            def realm = Jenkins.getInstance().getSecurityRealm()
            def user = realm.createAccount('${escapeString(username)}', '${escapeString(password)}')

            print(user)
        """
        
        def result = apiClient.runScript(script)

        if (result != username) {
            throw new RuntimeException("Error when creating user: $result")
        }
    }

    void grantPermission(String username, Permissions permission) {
        if (!isUsingMatrixBasedPermissions()) {
            log.debug("Is not using matrix based permission. Does not need to add permission.")
            return
        }

        log.debug("Grant user $username permission $permission")

        @Language("Groovy")
        def script = """
            import org.jenkinsci.plugins.matrixauth.PermissionEntry
            import org.jenkinsci.plugins.matrixauth.AuthorizationType

            def permissions = Jenkins.getInstance().getAuthorizationStrategy().getGrantedPermissionEntries()
            permissions.computeIfAbsent(${permission.toJenkinsPermissionEnum()}) {
              new HashSet<>()
            }
            print(permissions[${permission.toJenkinsPermissionEnum()}].add(new PermissionEntry(AuthorizationType.USER, '${escapeString(username)}')))
        """
        def result = apiClient.runScript(script)

        if (result !in ["true", "false"]) { // Both are valid return values for Set.add(). true == was already in set, false == was not already in set
            throw new RuntimeException("Failed to add permission $permission to $username: $result")
        }
    }

    boolean isUsingMatrixBasedPermissions() {
        def result = apiClient.runScript("print(Jenkins.getInstance().getAuthorizationStrategy().class)")

        if (!result.startsWith("class ")) {
            throw new RuntimeException("Error when trying to determine authorization strategy: $result")
        }

        return result == "class hudson.security.GlobalMatrixAuthorizationStrategy" || result == "class hudson.security.ProjectMatrixAuthorizationStrategy"
    }

    boolean isUsingCasSecurityRealm() {
        def result = apiClient.runScript("print(Jenkins.getInstance().getSecurityRealm().class)")

        if (!result.startsWith("class ")) {
            throw new RuntimeException("Error when trying to determine security realm: $result")
        }

        return result == "class org.jenkinsci.plugins.cas.CasSecurityRealm"
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

        String toJenkinsPermissionEnum() {
            return value
        }
    }
}
