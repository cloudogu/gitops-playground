package com.cloudogu.gitops.config

class Credentials {
    String username
    String password
    String secretNamespace
    String secretName
    String usernameKey
    String passwordKey

    Credentials(String username, String password, String secretName = '', String secretNamespace = '', String usernameKey = "username", String passwordKey = 'password') {
        this.username = username
        this.password = password
        this.secretNamespace = secretNamespace
        this.secretName = secretName
        this.usernameKey = usernameKey
        this.passwordKey = passwordKey
    }
}