package com.cloudogu.gitops.tools.core.argocd;

import java.nio.file.Path;

public record ArgoCDRepoLayout(String repoRootDir) {

  private static final String APPS_ARGOCD_DIR = "apps/argocd";

  private static final String APPLICATIONS_DIR = "applications";
  private static final String HELM_DIR = "argocd";
  private static final String MULTITENANT_DIR = "multiTenant";
  private static final String OPERATOR_DIR = "operator";
  private static final String PROJECTS_DIR = "projects";

  private static final String NETPOL_YAML = "templates/allow-namespaces.yaml";

  public String rootDir() {
    return repoRootDir;
  }

  public String argocdRoot() {
    return Path.of(repoRootDir, APPS_ARGOCD_DIR).toString();
  }

  public String operatorDir() {
    return Path.of(argocdRoot(), OPERATOR_DIR).toString();
  }

  public String operatorRbacDir() {
    return Path.of(operatorDir(), "rbac").toString();
  }

  public String operatorConfigFile() {
    return Path.of(operatorDir(), "argocd.yaml").toString();
  }

  public String multiTenantDir() {
    return Path.of(argocdRoot(), MULTITENANT_DIR).toString();
  }

  public String applicationsDir() {
    return Path.of(argocdRoot(), APPLICATIONS_DIR).toString();
  }

  public String projectsDir() {
    return Path.of(argocdRoot(), PROJECTS_DIR).toString();
  }

  public String helmDir() {
    return Path.of(argocdRoot(), HELM_DIR).toString();
  }

  public String helmValuesFile() {
    return Path.of(helmDir(), "values.yaml").toString();
  }

  public String chartYaml() {
    return Path.of(helmDir(), "Chart.yaml").toString();
  }

  public String netpolFile() {
    return Path.of(helmDir(), NETPOL_YAML).toString();
  }

  public static String argocdSubdirRel() {
    return APPS_ARGOCD_DIR;
  }

  public static String operatorRbacSubfolder() {
    return APPS_ARGOCD_DIR + "/" + OPERATOR_DIR + "/rbac";
  }

  public static String operatorRbacTenantSubfolder() {
    return operatorRbacSubfolder() + "/tenant";
  }
}
