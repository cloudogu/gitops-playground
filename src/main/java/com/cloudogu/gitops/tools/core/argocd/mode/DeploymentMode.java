package com.cloudogu.gitops.tools.core.argocd.mode;

import java.util.List;

public interface DeploymentMode {

	String ARGOCD_APPLICATION_CONTROLLER_SERVICE_ACCOUNT = "argocd-argocd-application-controller";

	List<String> ARGOCD_SERVICE_ACCOUNTS = List.of(
		"argocd-argocd-server",
		ARGOCD_APPLICATION_CONTROLLER_SERVICE_ACCOUNT,
		"argocd-applicationset-controller"
	);

	void createSCMCredentialsSecret();

	void generateRBAC();

	void updateManagedNamespaces();

	void applyBootstrapResources();
}
