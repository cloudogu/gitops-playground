package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.Tuple;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@Singleton
@Order(100)
@RequiredArgsConstructor
public class ArgoCDDestructionHandler implements DestructionHandler {

  private final ContextBuilder contextBuilder;
  private final K8sClient k8sClient;
  private final HelmClient helmClient;
  private final GitRepoFactory repoProvider;
  private final FileSystemUtils fileSystemUtils;
  private final GitHandler gitHandler;
  private DeploymentContext context;

  @Override
  public void destroy() {
    this.context = contextBuilder.build();

    String namePrefix = getConfig().getApplication().getNamePrefix();
    String argocdNamespace = namePrefix + getConfig().getFeatures().getArgocd().getNamespace();
    String jenkinsNamespace =
        Boolean.TRUE.equals(getConfig().getJenkins().getInternal())
            ? namePrefix + getConfig().getJenkins().getNamespace()
            : null;

    GitRepo repo = repoProvider.create("argocd/cluster-resources", gitHandler.getResourcesScm());
    try {
      repo.cloneRepo();
    } catch (Exception e) {
      throw new RuntimeException("Failed to clone argocd cluster-resources repo", e);
    }

    for (var app : k8sClient.getCustomResource("app")) {
      if ("bootstrap".equals(app.name())
          || "argocd".equals(app.name())
          || "projects".equals(app.name())) {
        continue;
      }

      k8sClient.patch(
          "app",
          app.name(),
          app.namespace(),
          "merge",
          Map.of(
              "metadata", Map.of("finalizers", List.of("resources-finalizer.argocd.argoproj.io"))));
    }

    List<Tuple<String, String>> appsToBeDeleted =
        List.of(
            new Tuple<>(argocdNamespace, "bootstrap"),
            new Tuple<>(argocdNamespace, "cluster-resources"),
            new Tuple<>(argocdNamespace, "example-apps"));

    for (var app : appsToBeDeleted) {
      k8sClient.delete("app", app.getV1(), app.getV2());
    }

    installArgoCDViaHelm(repo, argocdNamespace);
    helmClient.uninstall("argocd", "argocd");
    for (var project : k8sClient.getCustomResource("appprojects")) {
      k8sClient.delete("appproject", project.namespace(), project.name());
    }

    k8sClient.delete("app", argocdNamespace, "projects");
    k8sClient.delete("app", argocdNamespace, "argocd");

    if (jenkinsNamespace != null) {
      k8sClient.delete("secret", jenkinsNamespace, "jenkins-credentials");
    }
    k8sClient.delete("secret", argocdNamespace, "argocd-repo-creds-scm");
  }

  public void installArgoCDViaHelm(GitRepo repo, String argocdNamespace) {
    String umbrellaChartPath = Path.of(repo.getAbsoluteLocalRepoTmpDir(), "argocd/").toString();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> helmDependencies =
        (List<Map<String, Object>>)
            fileSystemUtils.readYaml(Path.of(umbrellaChartPath, "Chart.yaml")).get("dependencies");
    helmClient.addRepo("argo", (String) helmDependencies.get(0).get("repository"));
    helmClient.dependencyBuild(umbrellaChartPath);
    helmClient.upgrade("argocd", umbrellaChartPath, Map.of("namespace", argocdNamespace));
  }

  private Config getConfig() {
    return context.getConfig();
  }
}
