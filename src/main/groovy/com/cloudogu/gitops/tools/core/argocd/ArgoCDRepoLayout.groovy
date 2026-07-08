package com.cloudogu.gitops.tools.core.argocd

import java.nio.file.Path

class ArgoCDRepoLayout {

	private static final String APPS_ARGOCD_DIR = 'apps/argocd'

	private static final String APPLICATIONS_DIR = 'applications'
	private static final String HELM_DIR = 'argocd'
	private static final String MULTITENANT_DIR = 'multiTenant'
	private static final String OPERATOR_DIR = 'operator'
	private static final String PROJECTS_DIR = 'projects'

	// Relative to apps/argocd/argocd
	private static final String NETPOL_YAML = 'templates/allow-namespaces.yaml'

	private final String repoRootDir

	ArgoCDRepoLayout(String repoRootDir) {
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