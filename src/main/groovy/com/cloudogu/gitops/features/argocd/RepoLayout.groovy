package com.cloudogu.gitops.features.argocd

import java.nio.file.Path

class RepoLayout {
    private static final String APPS_MONITORING_DIR     = 'apps/monitoring'
    private static final String APPS_SECRETS_DIR        = 'apps/external-secrets'
    private static final String APPS_VAULT_DIR          = 'apps/vault'
    private static final String APPS_CERTMANAGER_DIR    = 'apps/cert-manager'
    private static final String APPS_JENKINS_DIR        = 'apps/jenkins'
    private static final String APPS_INGRESS_DIR        = 'apps/ingress'
    private static final String APPS_MAILHOG_DIR        = 'apps/mail'
    private static final String APPS_SCMMANAGER_DIR     = 'apps/scm-manager'
    private static final String APPS_ARGOCD_DIR         = 'apps/argocd'

    private static final String OPERATOR_DIR       = 'operator'
    private static final String MULTITENANT_DIR    = 'multiTenant'
    private static final String APPLICATIONS_DIR   = 'applications'
    private static final String PROJECTS_DIR       = 'projects'
    private static final String HELM_DIR           = 'argocd'          // argocd/argocd
    private static final String NETPOL_YAML = 'templates/allow-namespaces.yaml'

    private final String repoRootDir

    RepoLayout(String repoRootDir) {
        this.repoRootDir = repoRootDir
    }

    String rootDir() {
        repoRootDir
    }

    String argocdRoot() {
        Path.of(repoRootDir, APPS_ARGOCD_DIR).toString()
    }

    // --- folder ---

    String operatorDir() {
        Path.of(argocdRoot(), OPERATOR_DIR).toString()
    }

    String operatorRbacDir() {
        // "cluster-resources/apps/argocd/operator/rbac"
        Path.of(operatorDir(), "rbac").toString()
    }

    String operatorConfigFile() {
        // "cluster-resources/apps/argocd/operator/argocd.yaml"
        Path.of(operatorDir(), "argocd.yaml").toString()
    }

    String multiTenantDir() {
        Path.of(argocdRoot(), MULTITENANT_DIR).toString()
    }

    String applicationsDir() {
        Path.of(argocdRoot(), APPLICATIONS_DIR).toString()
    }

    String projectsDir() {
        Path.of(argocdRoot(), PROJECTS_DIR).toString()
    }

    String helmDir() {
        Path.of(argocdRoot(), HELM_DIR).toString()
    }

    String helmValuesFile() {
        // "cluster-resources/apps/argocd/argocd/values.yaml"
        Path.of(helmDir(), "values.yaml").toString()
    }

    String chartYaml() {
        Path.of(helmDir(), "Chart.yaml").toString()
    }

    String netpolFile() {
        Path.of(helmDir(), NETPOL_YAML).toString()
    }

    String monitoringDir() {
        Path.of(repoRootDir, APPS_MONITORING_DIR).toString()
    }

    String vaultDir() {
        Path.of(repoRootDir, APPS_VAULT_DIR).toString()
    }

    static String monitoringSubdirRel() {
        APPS_MONITORING_DIR
    }

    static String secretsSubdirRel() {
        APPS_SECRETS_DIR
    }
    static String vaultSubdirRel() {
        APPS_VAULT_DIR
    }

    static String certManagerSubdirRel() {
        APPS_CERTMANAGER_DIR
    }

    static String jenkinsSubdirRel() {
        APPS_JENKINS_DIR
    }

    static String ingressSubdirRel() {
        APPS_INGRESS_DIR
    }
    static String mailhogSubdirRel() {
        APPS_MAILHOG_DIR
    }
    static String scmManagerSubdirRel() {
        APPS_SCMMANAGER_DIR
    }
    static String argocdSubdirRel() {
        APPS_ARGOCD_DIR
    }

    // --- relative subfolders for RBAC (passed to RbacDefinition.withSubfolder) ---
    static String operatorRbacSubfolder() {
        // "argocd/operator/rbac"
        "${APPS_ARGOCD_DIR}/${OPERATOR_DIR}/rbac"
    }

    static String operatorRbacTenantSubfolder() {
        // "argocd/operator/rbac/tenant"
        "${operatorRbacSubfolder()}/tenant"
    }
}
