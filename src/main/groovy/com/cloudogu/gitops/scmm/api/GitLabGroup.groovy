package com.cloudogu.gitops.scmm.api

import com.fasterxml.jackson.annotation.JsonIgnore
import lombok.Getter
import lombok.Setter
import lombok.ToString

@Getter
@Setter
@ToString
class GitLabGroup {

    private Integer id       // The name of the group
    private String name       // The name of the group
    private String path       // The URL path (slug) for the group
    private String description // Optional: Description of the group
    private String visibility // Optional: "private", "internal", or "public"
    @JsonIgnore
    private GitLabGroup parent  // Optional: ID of the parent group if creating a subgroup

    GitLabGroup(String name, String path, GitLabGroup parent = null) {
        this.name = name
        this.path = path
        this.description = name
        this.visibility = "private"
        this.parent = parent
    }

    Integer getId() {
        return id
    }

    void setId(Integer id) {
        this.id = id
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

    GitLabGroup getParent() {
        return parent
    }

    void setParent(GitLabGroup parent) {
        this.parent = parent
    }
}
