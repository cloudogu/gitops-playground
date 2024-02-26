package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
@Order(100)
class ArgoCDDestructionHandler implements DestructionHandler {
    private K8sClient k8sClient
    private ScmmRepoProvider repoProvider
    private HelmClient helmClient
    private Configuration configuration
    private FileSystemUtils fileSystemUtils

    ArgoCDDestructionHandler(
            Configuration configuration,
            K8sClient k8sClient,
            ScmmRepoProvider repoProvider,
            HelmClient helmClient,
            FileSystemUtils fileSystemUtils
    ) {
        this.k8sClient = k8sClient
        this.repoProvider = repoProvider
        this.helmClient = helmClient
        this.configuration = configuration
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    void destroy() {

        def repo = repoProvider.getRepo("argocd/argocd")
        repo.cloneRepo()

        for (def app in k8sClient.getCustomResource("app")) {
            if (app.name == 'bootstrap' || app.name == 'argocd' || app.name == 'projects') {
                // we don't want bootstrap to kill everything
                // argocd and projects are needed for argocd to function and run finalizers
                continue
            }

            k8sClient.patch(
                    "app",
                    app.name,
                    app.namespace,
                    'merge',
                    [
                            metadata: [
                                    finalizers: [
                                            "resources-finalizer.argocd.argoproj.io"
                                    ]
                            ]
                    ]
            )
        }

        List<Tuple2<String, String>> appsToBeDeleted = [
                new Tuple2<String, String>("argocd", "bootstrap"), // first to prevent recreation
                new Tuple2<String, String>("argocd", "cluster-resources"),
                new Tuple2<String, String>("argocd", "example-apps"),
        ]

        for (def app in appsToBeDeleted) {
            k8sClient.delete("app", app.v1, app.v2)
        }

        installArgoCDViaHelm(repo)
        helmClient.uninstall('argocd', 'argocd')
        for (def project in k8sClient.getCustomResource('appprojects')) {
            k8sClient.delete("appproject", project.namespace, project.name)
        }

        k8sClient.delete("app", 'argocd', "projects")
        k8sClient.delete("app", 'argocd', "argocd")

        k8sClient.delete('secret', 'default', 'jenkins-credentials')
        k8sClient.delete('secret', 'default', 'argocd-repo-creds-scmm')
    }

    void installArgoCDViaHelm(ScmmRepo repo) {
        // this is a hack to be able to uninstall using helm
        def namePrefix = configuration.getNamePrefix()

        // Install umbrella chart from folder
        String umbrellaChartPath = Path.of(repo.getAbsoluteLocalRepoTmpDir(), 'argocd/')
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(Path.of(umbrellaChartPath, 'Chart.yaml'))['dependencies']
        helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
        helmClient.dependencyBuild(umbrellaChartPath)
        helmClient.upgrade('argocd', umbrellaChartPath, [namespace: "${namePrefix}argocd"])
    }
}
