package com.cloudogu.gitops.infrastructure.git.providers.scmmanager;

import java.util.ArrayList;
import java.util.List;

public class Permission {
    private final String name;
    private final Role role;
    private final List<String> verbs;
    private final boolean groupPermission;

    public Permission(String name, Role role) {
        this(name, role, false, new ArrayList<>());
    }

    public Permission(String name, Role role, boolean groupPermission) {
        this(name, role, groupPermission, new ArrayList<>());
    }

    public Permission(String name, Role role, boolean groupPermission, List<String> verbs) {
        this.name = name;
        this.role = role;
        this.verbs = verbs != null ? verbs : new ArrayList<>();
        this.groupPermission = groupPermission;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public List<String> getVerbs() {
        return verbs;
    }

    public boolean isGroupPermission() {
        return groupPermission;
    }

    @Override
    public String toString() {
        return "Permission{name='" + name + "', role=" + role + ", verbs=" + verbs + ", groupPermission=" + groupPermission + "}";
    }

    public enum Role {
        READ, WRITE, OWNER
    }
}
