package com.cloudogu.gitops.tools.core.argocd.mode;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.RbacDefinition;
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.Role;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout;
import com.cloudogu.gitops.utils.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SingleTenantMode implements DeploymentMode {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantMode.class);

    private static final List<String> ARGOCD_SERVICE_ACCOUNTS = List.of(
            "argocd-argocd-server",
            "argocd-argocd-application-controller",
            "argocd-applicationset-controller");

    private final Config config;
    private final K8sClient k8sClient;
    private final GitHandler gitHandler;
    private final RepositoryWorkspace repositoryWorkspace;
    private final ArgoCDRepoLayout clusterResourcesRepo;
    private final String namespace;

    public SingleTenantMode(Config config,
                            K8sClient k8sClient,
                            GitHandler gitHandler,
                            RepositoryWorkspace repositoryWorkspace,
                            ArgoCDRepoLayout clusterResourcesRepo,
                            String namespace) {
        this.config = config;
        this.k8sClient = k8sClient;
        this.gitHandler = gitHandler;
        this.repositoryWorkspace = repositoryWorkspace;
        this.clusterResourcesRepo = clusterResourcesRepo;
        this.namespace = namespace;
    }

    @Override
    public void createSCMCredentialsSecret() {
        log.debug("Creating repo credential secret that is used by ArgoCD to access repos in {}", config.getScm().getScmProviderType());

        createRepoCredentialsSecret("argocd-repo-creds-scm",
                namespace,
                gitHandler.getTenant().getUrl(),
                gitHandler.getTenant().getCredentials().getUsername(),
                gitHandler.getTenant().getCredentials().getPassword());
    }

    @Override
    public void generateRBAC() {
        log.debug("Generate RBAC permissions for ArgoCD in all managed namespaces");

        for (String ns : config.getApplication().getNamespaces().getActiveNamespaces()) {
            new RbacDefinition(Role.Variant.ARGOCD)
                    .withName("argocd")
                    .withNamespace(ns)
                    .withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
                    .withConfig(config)
                    .withRepo(repositoryWorkspace.getClusterResourcesRepository())
                    .withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
                    .generate();
        }

        if (Boolean.TRUE.equals(config.getApplication().getClusterAdmin())) {
            new RbacDefinition(Role.Variant.CLUSTER_ADMIN)
                    .withName("argocd-cluster-admin")
                    .withNamespace(namespace)
                    .withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
                    .withConfig(config)
                    .withRepo(repositoryWorkspace.getClusterResourcesRepository())
                    .withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
                    .generate();
        }
    }

    @Override
    public void updateManagedNamespaces() {
        log.debug("Updating managed namespaces in ArgoCD configuration secret.");

        k8sClient.patch("secret",
                "argocd-default-cluster-config",
                namespace,
                Map.of("stringData", Map.of("namespaces", String.join(",", config.getApplication().getNamespaces().getActiveNamespaces()))));
    }

    @Override
    public void applyBootstrapResources() {
        k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), "argocd.yaml").toString());
        k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), "bootstrap.yaml").toString());
    }

    private void createRepoCredentialsSecret(String secretName,
                                             String ns,
                                             String url,
                                             String username,
                                             String password) {
        k8sClient.createSecret("generic",
                secretName,
                ns,
                new Tuple<>("url", url),
                new Tuple<>("username", username),
                new Tuple<>("password", password));

        k8sClient.label("secret",
                secretName,
                ns,
                new Tuple<>("argocd.argoproj.io/secret-type", "repo-creds"));
    }
}
