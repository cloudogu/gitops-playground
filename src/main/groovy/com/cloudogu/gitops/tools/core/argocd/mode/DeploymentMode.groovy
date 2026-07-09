package com.cloudogu.gitops.tools.core.argocd.mode

interface DeploymentMode {

	void createSCMCredentialsSecret()

	void generateRBAC()

	void updateManagedNamespaces()

	void applyBootstrapResources()
}