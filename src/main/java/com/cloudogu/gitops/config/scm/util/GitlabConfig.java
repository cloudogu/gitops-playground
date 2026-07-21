package com.cloudogu.gitops.config.scm.util;

import com.cloudogu.gitops.config.Credentials;

public interface GitlabConfig {
  String getUrl();

  String getParentGroupId();

  String getDefaultVisibility();

  String getGitOpsUsername();

  Credentials getCredentials();
}
