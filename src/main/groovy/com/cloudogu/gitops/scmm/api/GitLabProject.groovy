package com.cloudogu.gitops.scmm.api
//TODO Remove 
import com.fasterxml.jackson.annotation.JsonProperty
import lombok.Getter
import lombok.Setter
import lombok.ToString

@ToString
@Getter
@Setter
class GitLabProject {
    private String name;
    @JsonProperty("namespace_id")// The name of the project
    private Integer namespaceID;   // The group ID where the project will reside
    private String description;    // Optional: Description of the project
    private String visibility;     // Optional: "private", "internal", or "public"

    GitLabProject(String name, Integer namespaceID) {
        this.name = name
        this.namespaceID = namespaceID
        this.description = name
        this.visibility = "private"
    }

    String getName() {
        return name
    }

    void setName(String name) {
        this.name = name
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

    Integer getNamespaceID() {
        return namespaceID
    }

    void setNamespaceID(Integer namespaceID) {
        this.namespaceID = namespaceID
    }
}

