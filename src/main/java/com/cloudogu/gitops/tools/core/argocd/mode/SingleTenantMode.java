package com.cloudogu.gitops.tools.core.argocd.mode;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.RbacDefinition;
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.Role;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDToolConfig;
import com.cloudogu.gitops.utils.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class SingleTenantMode implements DeploymentMode {

	private final ArgoCDToolConfig config;
	private final K8sClient k8sClient;
	private final GitHandler gitHandler;
	private final RepositoryWorkspace repositoryWorkspace;
	private final ArgoCDRepoLayout clusterResourcesRepo;
	private final String namespace;

	@Override
	public void createSCMCredentialsSecret() {
		log.debug(
			"Creating repo credential secret that is used by ArgoCD to access repos in {}", config.scmProviderType()
		);

		createRepoCredentialsSecret(
			"argocd-repo-creds-scm", namespace, gitHandler.getTenant()
														  .getUrl(), gitHandler.getTenant()
																			   .getCredentials()
																			   .getUsername(), gitHandler.getTenant()
																										 .getCredentials()
																										 .getPassword()
		);
	}

	@Override
	public void generateRBAC() {
		log.debug("Generate RBAC permissions for ArgoCD in all managed namespaces");

		for (String ns : config.activeNamespaces()) {
			new RbacDefinition(Role.Variant.ARGOCD).withName("argocd")
												   .withNamespace(ns)
												   .withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
												   .withTemplateConfig(config.rbacTemplateConfig())
												   .withRepo(repositoryWorkspace.getClusterResourcesRepository())
												   .withSubfolder(ArgoCDRepoLayout.operatorRbacSubfolder())
												   .generate();
		}

		if (config.clusterAdmin()) {
			new RbacDefinition(Role.Variant.CLUSTER_ADMIN).withName("argocd-cluster-admin")
														  .withNamespace(namespace)
														  .withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
														  .withTemplateConfig(config.rbacTemplateConfig())
														  .withRepo(repositoryWorkspace.getClusterResourcesRepository())
														  .withSubfolder(ArgoCDRepoLayout.operatorRbacSubfolder())
														  .generate();
		}
	}

	@Override
	public void updateManagedNamespaces() {
		log.debug("Updating managed namespaces in ArgoCD configuration secret.");

		k8sClient.patch(
			"secret", "argocd-default-cluster-config", namespace, Map.of(
				"stringData", Map.of(
					"namespaces", String.join(
						",", config.activeNamespaces()
					)
				)
			)
		);
	}

	@Override
	public void applyBootstrapResources() {
		k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), "argocd.yaml").toString());
		k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), "bootstrap.yaml").toString());
	}

	private void createRepoCredentialsSecret(
		String secretName,
		String ns,
		String url,
		String username,
		String password) {
		k8sClient.createSecret(
			"generic",
			secretName,
			ns,
			new Tuple<>("url", url),
			new Tuple<>("username", username),
			new Tuple<>("password", password)
		);

		k8sClient.label("secret", secretName, ns, new Tuple<>("argocd.argoproj.io/secret-type", "repo-creds"));
	}
}
