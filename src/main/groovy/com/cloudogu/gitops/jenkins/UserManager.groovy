package com.cloudogu.gitops.jenkins

import jakarta.inject.Singleton

@Singleton
class UserManager {
    private ApiClient apiClient

    UserManager(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void createUser(String username, String password) {
        if (username.contains("\\") || password.contains("\\")) {
            // We don't want get in trouble with escaping,
            // e.g. `foo\'foo` => `foo\\'foo`. Now we would have a backslash followed by an unescaped quote.
            throw new IllegalArgumentException("Backslashes within the username or password are forbidden.")
        }

        def result = apiClient.runScript("""
            def realm = Jenkins.getInstance().getSecurityRealm()
            def user = realm.createAccount('${escapeString(username)}', '${escapeString(password)}')

            return user
        """)

        if (result != "Result: $username\n") {
            throw new RuntimeException("Error when creating user: $result")
        }
    }

    private String escapeString(String str) {
        return str.replace("'", "\\'")
    }
}
