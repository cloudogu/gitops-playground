package com.cloudogu.gitops.config

class Credentials {
    String username
    String password

    Credentials(String username, String password) {
        this.username = username
        this.password = password
    }
}