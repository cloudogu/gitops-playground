package com.cloudogu.gitops.infrastructure.git.providers.scmmanager;

import java.util.ArrayList;
import java.util.List;

public record Permission(String name, Role role, boolean groupPermission, List<String> verbs) {

  public Permission(String name, Role role) {
    this(name, role, false, new ArrayList<>());
  }

  public Permission(String name, Role role, boolean groupPermission) {
    this(name, role, groupPermission, new ArrayList<>());
  }

  public Permission {
    verbs = verbs != null ? List.copyOf(verbs) : List.of();
  }

  public enum Role {
    READ,
    WRITE,
    OWNER
  }
}
