package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api

import com.fasterxml.jackson.annotation.JsonProperty

class ScmManagerUser {
    var name: String? = null
    var displayName: String? = null
    var mail: String? = null
    var external: Boolean = false
    var password: String? = null
    var active: Boolean = true
    
    @JsonProperty("_links")
    var _links: Map<String, Any> = emptyMap()

    // Standard no-arg constructor
    constructor()

    // Map-constructor for Groovy @CompileStatic map-coercion
    constructor(map: Map<String, Any?>) {
        (map["name"] as? String)?.let { this.name = it }
        (map["displayName"] as? String)?.let { this.displayName = it }
        (map["mail"] as? String)?.let { this.mail = it }
        (map["external"] as? Boolean)?.let { this.external = it }
        (map["password"] as? String)?.let { this.password = it }
        (map["active"] as? Boolean)?.let { this.active = it }
        (map["_links"] as? Map<*, *>)?.let { 
            @Suppress("UNCHECKED_CAST")
            this._links = it as Map<String, Any> 
        }
    }
}
