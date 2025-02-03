package com.cloudogu.gitops.scmm.api

import com.fasterxml.jackson.annotation.JsonProperty

class GitLabGroup {

    private String name       // The name of the group
    private String path       // The URL path (slug) for the group
    private String description // Optional: Description of the group
    private String visibility // Optional: "private", "internal", or "public"

    @JsonProperty("parent_id")
    private Integer parentId  // Optional: ID of the parent group if creating a subgroup

    GitLabGroup(String name, String path, Integer parentId) {
        this.name = name
        this.path = path
        this.description = name
        this.visibility = "private"
        this.parentId = parentId
    }

    String getName() {
        return name
    }

    void setName(String name) {
        this.name = name
    }

    String getPath() {
        return path
    }

    void setPath(String path) {
        this.path = path
    }

    String getDescription() {
        return description
    }

    void setDescription(String description) {
        this.description = description
    }

    String getVisibility() {
        return visibility
    }

    void setVisibility(String visibility) {
        this.visibility = visibility
    }

    Integer getParentId() {
        return parentId
    }

    void setParentId(Integer parentId) {
        this.parentId = parentId
    }
}
