package com.cloudogu.gitops.config

class Credentials {
    final String username
    final String password

    Credentials(String username, String password) {
        this.username = username
        this.password = password
    }
}
