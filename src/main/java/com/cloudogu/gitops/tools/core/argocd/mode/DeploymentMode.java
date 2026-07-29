package com.cloudogu.gitops.tools.core.argocd.mode;

import java.util.List;

public interface DeploymentMode {

	List<String> ARGOCD_SERVICE_ACCOUNTS = List.of("argocd-argocd-server", "argocd-argocd-application-controller", "argocd-applicationset-controller");

	void createSCMCredentialsSecret();

	void generateRBAC();

	void updateManagedNamespaces();

	void applyBootstrapResources();
}
