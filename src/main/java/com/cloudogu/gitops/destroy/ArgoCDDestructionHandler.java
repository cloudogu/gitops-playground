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

@Singleton
@Order(100)
@SuppressWarnings({"rawtypes", "unchecked"})
public class ArgoCDDestructionHandler implements DestructionHandler {

    private final K8sClient k8sClient;
    private final HelmClient helmClient;
    private final GitRepoFactory repoProvider;
    private final ContextBuilder contextBuilder;
    private final FileSystemUtils fileSystemUtils;
    private final GitHandler gitHandler;
    private DeploymentContext context;

    public ArgoCDDestructionHandler(ContextBuilder contextBuilder,
                                   K8sClient k8sClient,
                                   HelmClient helmClient,
                                   GitRepoFactory repoProvider,
                                   FileSystemUtils fileSystemUtils,
                                   GitHandler gitHandler) {
        this.k8sClient = k8sClient;
        this.helmClient = helmClient;
        this.repoProvider = repoProvider;
        this.contextBuilder = contextBuilder;
        this.fileSystemUtils = fileSystemUtils;
        this.gitHandler = gitHandler;
    }

    @Override
    public void destroy() {
        this.context = contextBuilder.build();

        GitRepo repo = repoProvider.create("argocd/cluster-resources", gitHandler.getResourcesScm());
        try {
            repo.cloneRepo();
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone argocd cluster-resources repo", e);
        }

        for (var app : k8sClient.getCustomResource("app")) {
            if ("bootstrap".equals(app.getName()) || "argocd".equals(app.getName()) || "projects".equals(app.getName())) {
                continue;
            }

            k8sClient.patch("app",
                    app.getName(),
                    app.getNamespace(),
                    "merge",
                    Map.of("metadata", Map.of("finalizers", List.of("resources-finalizer.argocd.argoproj.io"))));
        }

        List<Tuple<String, String>> appsToBeDeleted = List.of(
                new Tuple<>("argocd", "bootstrap"),
                new Tuple<>("argocd", "cluster-resources"),
                new Tuple<>("argocd", "example-apps")
        );

        for (var app : appsToBeDeleted) {
            k8sClient.delete("app", app.getV1(), app.getV2());
        }

        installArgoCDViaHelm(repo);
        helmClient.uninstall("argocd", "argocd");
        for (var project : k8sClient.getCustomResource("appprojects")) {
            k8sClient.delete("appproject", project.getNamespace(), project.getName());
        }

        k8sClient.delete("app", "argocd", "projects");
        k8sClient.delete("app", "argocd", "argocd");

        k8sClient.delete("secret", "default", "jenkins-credentials");
        k8sClient.delete("secret", "default", "argocd-repo-creds-scm");
    }

    public void installArgoCDViaHelm(GitRepo repo) {
        String namePrefix = getConfig().getApplication().getNamePrefix();
        String argocdNamespace = namePrefix + getConfig().getFeatures().getArgocd().getNamespace();
        String umbrellaChartPath = Path.of(repo.getAbsoluteLocalRepoTmpDir(), "argocd/").toString();

        List<Map<String, Object>> helmDependencies = (List<Map<String, Object>>) fileSystemUtils.readYaml(Path.of(umbrellaChartPath, "Chart.yaml")).get("dependencies");
        helmClient.addRepo("argo", (String) helmDependencies.get(0).get("repository"));
        helmClient.dependencyBuild(umbrellaChartPath);
        helmClient.upgrade("argocd", umbrellaChartPath, Map.of("namespace", argocdNamespace));
    }

    private Config getConfig() {
        return context.getConfig();
    }
}
