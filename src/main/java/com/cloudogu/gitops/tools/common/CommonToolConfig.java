package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;

public class CommonToolConfig extends Tool {

  @Override
  public void preConfigInit(Config configToSet) {
    validateConfig(configToSet);
  }

  /**
   * Make sure that config does not contain contradictory values. Throws RuntimeException with
   * meaningful message, if invalid.
   */
  public void validateConfig(Config configToSet) {
    validateMirrorReposHelmChartFolderSet(configToSet);
  }

  private static void validateMirrorReposHelmChartFolderSet(Config configToSet) {
    if (configToSet.getApplication().getMirrorRepos()
        && (configToSet.getApplication().getLocalHelmChartFolder() == null
            || configToSet.getApplication().getLocalHelmChartFolder().isEmpty())) {
      // This should only happen when run outside the image, i.e. during development
      throw new RuntimeException(
          "Missing config for localHelmChartFolder.\n"
              + "Either run inside the official container image or setting env var "
              + "LOCAL_HELM_CHART_FOLDER='charts' after running 'scripts/downloadHelmCharts.sh' from the repo");
    }
  }

  @Override
  public boolean isEnabled(DeploymentContext context) {
    return false;
  }
}
