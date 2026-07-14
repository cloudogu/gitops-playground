package com.cloudogu.gitops.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription

class Credentials {

    @field:JsonPropertyDescription(ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    var username: String? = null

    @field:JsonPropertyDescription(ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    @field:JsonIgnore
    var password: String? = null

    @field:JsonPropertyDescription(ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    var secretNamespace: String? = null

    @field:JsonPropertyDescription(ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    var secretName: String? = null

    @field:JsonPropertyDescription(ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    var usernameKey: String = "username"

    @field:JsonPropertyDescription(ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION)
    var passwordKey: String = "password"

    // Standard no-arg constructor
    constructor()

    // Full constructor
    @JvmOverloads
    constructor(
        username: String?,
        password: String?,
        secretName: String? = "",
        secretNamespace: String? = "",
        usernameKey: String = "username",
        passwordKey: String = "password"
    ) {
        this.username = username
        this.password = password
        this.secretNamespace = secretNamespace
        this.secretName = secretName
        this.usernameKey = usernameKey
        this.passwordKey = passwordKey
    }

    // UnsafeCredentials copy constructor
    constructor(unsafeCredentials: Credentials) {
        this.secretNamespace = unsafeCredentials.secretNamespace
        this.secretName = unsafeCredentials.secretName
        this.usernameKey = unsafeCredentials.usernameKey
        this.passwordKey = unsafeCredentials.passwordKey
    }

    // Map-constructor for Groovy map-coercion/named arguments
    constructor(map: Map<String, Any?>) {
        (map["username"] as? String)?.let { this.username = it }
        (map["password"] as? String)?.let { this.password = it }
        (map["secretNamespace"] as? String)?.let { this.secretNamespace = it }
        (map["secretName"] as? String)?.let { this.secretName = it }
        (map["usernameKey"] as? String)?.let { this.usernameKey = it }
        (map["passwordKey"] as? String)?.let { this.passwordKey = it }
    }

    override fun toString(): String {
        return "Credentials(username=$username, password=[PROTECTED], secretNamespace=$secretNamespace, secretName=$secretName, usernameKey=$usernameKey, passwordKey=$passwordKey)"
    }
}
