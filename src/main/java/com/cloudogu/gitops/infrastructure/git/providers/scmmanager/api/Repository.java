package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

public class Repository {
    private final String name;
    private final String namespace;
    private final String type;
    private final String contact;
    private final String description;

    public Repository(String namespace, String name) {
        this(namespace, name, null, null, "git");
    }

    public Repository(String namespace, String name, String description) {
        this(namespace, name, description, null, "git");
    }

    public Repository(String namespace, String name, String description, String contact) {
        this(namespace, name, description, contact, "git");
    }

    public Repository(String namespace, String name, String description, String contact, String type) {
        this.namespace = namespace;
        this.name = name;
        this.type = type != null ? type : "git";
        this.contact = contact;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getType() {
        return type;
    }

    public String getContact() {
        return contact;
    }

    public String getDescription() {
        return description;
    }

    public String getFullRepoName() {
        return namespace + "/" + name;
    }

    @Override
    public String toString() {
        return "Repository{name='" + name + "', namespace='" + namespace + "', type='" + type + "', contact='" + contact + "', description='" + description + "'}";
    }
}
