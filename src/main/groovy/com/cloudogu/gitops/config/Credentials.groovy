package com.cloudogu.gitops.config

import com.fasterxml.jackson.annotation.JsonPropertyDescription

import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION

class Credentials {
    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    String username
    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    String password
    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    String secretNamespace
    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    String secretName
    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    String usernameKey
    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    String passwordKey


    Credentials() {}

    Credentials(String username, String password, String secretName = '', String secretNamespace = '', String usernameKey = "username", String passwordKey = 'password') {
        this.username = username
        this.password = password
        this.secretNamespace = secretNamespace
        this.secretName = secretName
        this.usernameKey = usernameKey
        this.passwordKey = passwordKey
    }

    @Override
    String toString() {
        return "Credentials{" +
                "username='" + username + '\'' +
                ", password='*****'" +
                ", secretNamespace='" + secretNamespace + '\'' +
                ", secretName='" + secretName + '\'' +
                ", usernameKey='" + usernameKey + '\'' +
                ", passwordKey='" + passwordKey + '\'' +
                '}';
    }

}