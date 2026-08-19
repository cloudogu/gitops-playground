package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient.CustomResource;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.Tuple;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Singleton
@Order(100)
@RequiredArgsConstructor
public class ArgoCDDestructionHandler implements DestructionHandler {

	private static final String ARGOCD = "argocd";

	private final Config config;
	private final K8sClient k8sClient;
	private final HelmClient helmClient;
	private final GitRepoFactory repoProvider;
	private final FileSystemUtils fileSystemUtils;
	private final GitHandler gitHandler;

	@Override
	public void destroy() {
		String namePrefix = config.getApplication().getNamePrefix();

		GitRepo repo = repoProvider.create("argocd/cluster-resources", gitHandler.getResourcesScm());
		try {
			repo.cloneRepo();
		} catch (Exception e) {
			throw new RuntimeException("Failed to clone argocd cluster-resources repo", e);
		}

		for (CustomResource app : k8sClient.getCustomResource("app")) {
			if ("bootstrap".equals(app.name()) || ARGOCD.equals(app.name()) || "projects".equals(app.name())) {
				continue;
			}

			k8sClient.patch(
				"app",
				app.name(),
				app.namespace(),
				"merge",
				Map.of("metadata", Map.of("finalizers", List.of("resources-finalizer.argocd.argoproj.io")))
			);
		}

		String argocdNamespace = namePrefix + config.getFeatures().getArgocd().getNamespace();
		List<Tuple<String, String>> appsToBeDeleted = List.of(
			new Tuple<>(argocdNamespace, "bootstrap"),
			new Tuple<>(argocdNamespace, "cluster-resources"),
			new Tuple<>(argocdNamespace, "example-apps")
		);

		for (Tuple<String, String> app : appsToBeDeleted) {
			k8sClient.delete("app", app.getV1(), app.getV2());
		}

		installArgoCDViaHelm(repo, argocdNamespace);
		helmClient.uninstall(ARGOCD, ARGOCD);
		for (CustomResource project : k8sClient.getCustomResource("appprojects")) {
			k8sClient.delete("appproject", project.namespace(), project.name());
		}

		k8sClient.delete("app", argocdNamespace, "projects");
		k8sClient.delete("app", argocdNamespace, ARGOCD);

		String jenkinsNamespace = config.getJenkins().getInternal() ? (namePrefix + config.getJenkins()
		                                                                                            .getNamespace()) : null;
		if (jenkinsNamespace != null) {
			k8sClient.delete("secret", jenkinsNamespace, "jenkins-credentials");
		}
		k8sClient.delete("secret", argocdNamespace, "argocd-repo-creds-scm");
	}

	public void installArgoCDViaHelm(GitRepo repo, String argocdNamespace) {
		String umbrellaChartPath = Path.of(repo.getAbsoluteLocalRepoTmpDir(), "argocd/").toString();

		List<Map<String, Object>> helmDependencies = MapUtils.asListOfStringObjectMaps(fileSystemUtils.readYaml(Path.of(
																										  umbrellaChartPath,
																										  "Chart.yaml"
																									  ))
		                                                                                              .get(
																										  "dependencies"));
		helmClient.addRepo("argo", (String) helmDependencies.get(0).get("repository"));
		helmClient.dependencyBuild(umbrellaChartPath);
		helmClient.upgrade(ARGOCD, umbrellaChartPath, Map.of("namespace", argocdNamespace));
	}

}
