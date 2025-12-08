package com.cloudogu.gitops.features.argocd

import java.nio.file.Path

class ArgoCDRepoLayout {

    private static final String ARGOCD_SUBDIR      = 'argocd'
    private static final String OPERATOR_DIR       = 'operator'
    private static final String MULTITENANT_DIR    = 'multiTenant'
    private static final String APPLICATIONS_DIR   = 'applications'
    private static final String PROJECTS_DIR       = 'projects'
    private static final String HELM_DIR           = 'argocd'          // argocd/argocd
    private static final String NETPOL_REL         = 'templates/allow-namespaces.yaml'
    private static final String NAMESPACES_YAML    = 'misc/namespaces.yaml'
    private static final String MONITORING_REL     = 'apps/monitoring'

    private final String repoRootDir

    ArgoCDRepoLayout(String repoRootDir) {
        this.repoRootDir = repoRootDir
    }

    String rootDir() {
        repoRootDir
    }

    String argocdRoot() {
        Path.of(repoRootDir, ARGOCD_SUBDIR).toString()
    }

    // --- folder ---

    String operatorDir() {
        Path.of(argocdRoot(), OPERATOR_DIR).toString()
    }

    String operatorConfigFile() {
        // "cluster-resources/argocd/operator/argocd.yaml"
        Path.of(operatorDir(), "argocd.yaml").toString()
    }

    String operatorRbacDir() {
        // "cluster-resources/argocd/operator/rbac"
        Path.of(operatorDir(), "rbac").toString()
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

    String monitoringDir() {
        Path.of(repoRootDir, MONITORING_REL).toString()
    }

    static String monitoringSubdirRel() {
        MONITORING_REL   // z.B. "apps/monitoring"
    }

    // --- files ---

    String chartYaml() {
        Path.of(helmDir(), "Chart.yaml").toString()
    }

    String netpolFile() {
        Path.of(helmDir(), NETPOL_REL).toString()
    }

    String namespacesYaml() {
        Path.of(repoRootDir, NAMESPACES_YAML).toString()
    }

    // --- dedicated instance bootstrap ---

    String dedicatedTenantProject() {
        Path.of(argocdRoot(), MULTITENANT_DIR, "central/projects/tenant.yaml").toString()
    }

    String dedicatedBootstrapApp() {
        Path.of(argocdRoot(), MULTITENANT_DIR, "central/applications/bootstrap.yaml").toString()
    }


    // --- relative subfolders for RBAC (passed to RbacDefinition.withSubfolder) ---
    static String operatorRbacSubfolder() {
        // "argocd/operator/rbac"
        "${ARGOCD_SUBDIR}/${OPERATOR_DIR}/rbac"
    }

    static String operatorRbacTenantSubfolder() {
        // "argocd/operator/rbac/tenant"
        "${operatorRbacSubfolder()}/tenant"
    }
}
