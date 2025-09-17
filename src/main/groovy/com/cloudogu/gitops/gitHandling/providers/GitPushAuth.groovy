package com.cloudogu.gitops.gitHandling.providers

class GitPushAuth {
    final String username
    final String password

    GitPushAuth(String username, String password) {
        this.username = username;
        this.password = password
    }
}
