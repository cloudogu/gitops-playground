package com.cloudogu.gitops.tools.core.argocd

import java.nio.file.Path
import groovy.transform.CompileStatic

@CompileStatic
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
		return repoRootDir
	}

	String argocdRoot() {
		return Path.of(repoRootDir, APPS_ARGOCD_DIR).toString()
	}

	// --- folder ---

	String operatorDir() {
		return Path.of(argocdRoot(), OPERATOR_DIR).toString()
	}

	String operatorRbacDir() {
		// "cluster-resources/apps/argocd/operator/rbac"
		return Path.of(operatorDir(), 'rbac').toString()
	}

	String operatorConfigFile() {
		// "cluster-resources/apps/argocd/operator/argocd.yaml"
		return Path.of(operatorDir(), 'argocd.yaml').toString()
	}

	String multiTenantDir() {
		return Path.of(argocdRoot(), MULTITENANT_DIR).toString()
	}

	String applicationsDir() {
		return Path.of(argocdRoot(), APPLICATIONS_DIR).toString()
	}

	String projectsDir() {
		return Path.of(argocdRoot(), PROJECTS_DIR).toString()
	}

	String helmDir() {
		return Path.of(argocdRoot(), HELM_DIR).toString()
	}

	String helmValuesFile() {
		// "cluster-resources/apps/argocd/argocd/values.yaml"
		return Path.of(helmDir(), 'values.yaml').toString()
	}

	String chartYaml() {
		return Path.of(helmDir(), 'Chart.yaml').toString()
	}

	String netpolFile() {
		return Path.of(helmDir(), NETPOL_YAML).toString()
	}

	static String argocdSubdirRel() {
		return APPS_ARGOCD_DIR
	}

	// --- relative subfolders for RBAC (passed to RbacDefinition.withSubfolder) ---
	static String operatorRbacSubfolder() {
		// "argocd/operator/rbac"
		return "${APPS_ARGOCD_DIR}/${OPERATOR_DIR}/rbac"
	}

	static String operatorRbacTenantSubfolder() {
		// "argocd/operator/rbac/tenant"
		return "${operatorRbacSubfolder()}/tenant"
	}
}