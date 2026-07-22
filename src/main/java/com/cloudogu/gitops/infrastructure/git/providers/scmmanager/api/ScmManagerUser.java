package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ScmManagerUser {
  private String name;
  private String displayName;
  private String mail;
  private boolean external;
  private String password;
  private boolean active = true;

  @JsonProperty("_links")
  private Map<String, Object> links = new HashMap<>();
}
