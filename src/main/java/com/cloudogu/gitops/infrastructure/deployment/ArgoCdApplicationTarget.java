package com.cloudogu.gitops.infrastructure.deployment;

public class ArgoCdApplicationTarget {

    private final String applicationName;
    private final String namespace;
    private final String project;
    private final boolean createDestinationNamespace;

    public ArgoCdApplicationTarget(String applicationName,
                                   String namespace,
                                   String project,
                                   boolean createDestinationNamespace) {
        this.applicationName = applicationName;
        this.namespace = namespace;
        this.project = project;
        this.createDestinationNamespace = createDestinationNamespace;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getProject() {
        return project;
    }

    public boolean isCreateDestinationNamespace() {
        return createDestinationNamespace;
    }
}
