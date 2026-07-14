package com.cloudogu.gitops.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION;

public class Credentials {

    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    private String username;

    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    @JsonIgnore
    private String password;

    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    private String secretNamespace;

    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    private String secretName;

    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    private String usernameKey = "username";

    @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    private String passwordKey = "password";

    public Credentials() {}

    public Credentials(String username, String password) {
        this(username, password, "", "", "username", "password");
    }

    public Credentials(String username, String password, String secretName) {
        this(username, password, secretName, "", "username", "password");
    }

    public Credentials(String username, String password, String secretName, String secretNamespace) {
        this(username, password, secretName, secretNamespace, "username", "password");
    }

    public Credentials(String username, String password, String secretName, String secretNamespace, String usernameKey) {
        this(username, password, secretName, secretNamespace, usernameKey, "password");
    }

    public Credentials(String username, String password, String secretName, String secretNamespace, String usernameKey, String passwordKey) {
        this.username = username;
        this.password = password;
        this.secretNamespace = secretNamespace;
        this.secretName = secretName;
        this.usernameKey = usernameKey;
        this.passwordKey = passwordKey;
    }

    public Credentials(Credentials unsafeCredentials) {
        if (unsafeCredentials != null) {
            this.secretNamespace = unsafeCredentials.secretNamespace;
            this.secretName = unsafeCredentials.secretName;
            this.usernameKey = unsafeCredentials.usernameKey;
            this.passwordKey = unsafeCredentials.passwordKey;
        }
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSecretNamespace() {
        return secretNamespace;
    }

    public void setSecretNamespace(String secretNamespace) {
        this.secretNamespace = secretNamespace;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public void setUsernameKey(String usernameKey) {
        this.usernameKey = usernameKey;
    }

    public String getPasswordKey() {
        return passwordKey;
    }

    public void setPasswordKey(String passwordKey) {
        this.passwordKey = passwordKey;
    }

    @Override
    public String toString() {
        return "Credentials{" +
                "username='" + username + '\'' +
                ", secretNamespace='" + secretNamespace + '\'' +
                ", secretName='" + secretName + '\'' +
                ", usernameKey='" + usernameKey + '\'' +
                ", passwordKey='" + passwordKey + '\'' +
                '}';
    }
}
