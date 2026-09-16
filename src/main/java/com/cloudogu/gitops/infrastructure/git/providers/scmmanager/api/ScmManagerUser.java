package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Request payload describing an SCM-Manager user account, created via {@link UsersApi}.
 */
@Getter
@Setter
@NoArgsConstructor
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
