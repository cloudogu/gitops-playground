package com.cloudogu.gitops.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION;

@Getter
@Setter
@ToString(exclude = "password")
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
}
