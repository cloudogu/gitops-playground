package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
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

  public Repository(
      String namespace, String name, String description, String contact, String type) {
    this.namespace = namespace;
    this.name = name;
    this.type = type != null ? type : "git";
    this.contact = contact;
    this.description = description;
  }

  public String getFullRepoName() {
    return namespace + "/" + name;
  }
}
