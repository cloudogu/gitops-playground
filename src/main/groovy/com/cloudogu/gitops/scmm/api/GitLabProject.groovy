package com.cloudogu.gitops.scmm.api

class GitLabProject {
    private String name;           // The name of the project
    private Integer groupId;   // The group ID where the project will reside
    private String description;    // Optional: Description of the project
    private String visibility;     // Optional: "private", "internal", or "public"
    private boolean initializeWithReadme; // Optional: Initialize the project with a README file

    GitLabProject(String name, Integer groupId) {
        this.name = name
        this.groupId = groupId
        this.description = name
        this.visibility = "private"
        this.initializeWithReadme = true

    }

    String getName() {
        return name
    }

    void setName(String name) {
        this.name = name
    }

    Integer getNamespaceId() {
        return groupId
    }

    void setNamespaceId(Integer groupId) {
        this.groupId = groupId
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

    boolean getInitializeWithReadme() {
        return initializeWithReadme
    }

    void setInitializeWithReadme(boolean initializeWithReadme) {
        this.initializeWithReadme = initializeWithReadme
    }

    @Override
    public String toString() {
        return "GitLabProject{" +
                "name='" + name + '\'' +
                ", groupId=" + groupId +
                ", description='" + description + '\'' +
                ", visibility='" + visibility + '\'' +
                ", initializeWithReadme=" + initializeWithReadme +
                '}';
    }
}

