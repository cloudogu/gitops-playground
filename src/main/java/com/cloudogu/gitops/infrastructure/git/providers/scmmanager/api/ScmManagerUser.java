package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

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
    private boolean external = false;
    private String password;
    private boolean active = true;
    private Map<String, Object> _links = new HashMap<>();
}
