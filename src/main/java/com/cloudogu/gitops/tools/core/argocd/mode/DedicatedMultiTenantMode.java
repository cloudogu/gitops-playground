package com.cloudogu.gitops.tools.core.argocd.mode;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.RbacDefinition;
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.Role;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoSetup;
import com.cloudogu.gitops.utils.Tuple;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class DedicatedMultiTenantMode implements DeploymentMode {

  private final Config config;
  private final K8sClient k8sClient;
  private final GitHandler gitHandler;
  private final RepositoryWorkspace repositoryWorkspace;
  private final ArgoCDRepoSetup repoSetup;
  private final ArgoCDRepoLayout clusterResourcesRepo;
  private final String namespace;

  @Override
  public void createSCMCredentialsSecret() {
    log.debug(
        "Creating tenant repo credential secret that is used by tenant ArgoCD to access repos in {}",
        config.getScm().getScmProviderType());

    createRepoCredentialsSecret(
        "argocd-repo-creds-scm",
        namespace,
        gitHandler.getTenant().getUrl(),
        gitHandler.getTenant().getCredentials().getUsername(),
        gitHandler.getTenant().getCredentials().getPassword());

    log.debug(
        "Creating central repo credential secret that is used by central ArgoCD to access repos in {}",
        config.getScm().getScmProviderType());

    createRepoCredentialsSecret(
        "argocd-repo-creds-central-scm",
        config.getMultiTenant().getCentralArgocdNamespace(),
        gitHandler.getCentral().getUrl(),
        gitHandler.getCentral().getCredentials().getUsername(),
        gitHandler.getCentral().getCredentials().getPassword());
  }

  @Override
  public void generateRBAC() {
    log.debug("Generate RBAC permissions for tenant ArgoCD and central ArgoCD.");

    generateTenantArgoCDRBAC();
    generateCentralArgoCDRBAC();
  }

  @Override
  public void updateManagedNamespaces() {
    log.debug("Updating managed namespaces in tenant ArgoCD configuration secret.");

    k8sClient.patch(
        "secret",
        "argocd-default-cluster-config",
        namespace,
        Map.of(
            "stringData",
            Map.of(
                "namespaces",
                String.join(",", config.getApplication().getNamespaces().getTenantNamespaces()))));

    updateCentralManagedNamespaces();
  }

  @Override
  public void applyBootstrapResources() {
    k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), "tenant.yaml").toString());
    k8sClient.applyYaml(
        Path.of(clusterResourcesRepo.applicationsDir(), "bootstrap.yaml").toString());

    ArgoCDRepoLayout tenantRepoLayout = repoSetup.tenantRepoLayout();
    k8sClient.applyYaml(Path.of(tenantRepoLayout.projectsDir(), "argocd.yaml").toString());
    k8sClient.applyYaml(Path.of(tenantRepoLayout.applicationsDir(), "bootstrap.yaml").toString());
  }

  private void generateTenantArgoCDRBAC() {
    for (String ns : config.getApplication().getNamespaces().getTenantNamespaces()) {
      new RbacDefinition(Role.Variant.ARGOCD)
          .withName("argocd")
          .withNamespace(ns)
          .withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
          .withConfig(config)
          .withRepo(repositoryWorkspace.getClusterResourcesRepository())
          .withSubfolder(clusterResourcesRepo.operatorRbacTenantSubfolder())
          .generate();
    }
  }

  private void generateCentralArgoCDRBAC() {
    for (String ns : config.getApplication().getNamespaces().getActiveNamespaces()) {
      log.debug("Generate RBAC permissions for centralized ArgoCD to access tenant ArgoCDs");

      new RbacDefinition(Role.Variant.ARGOCD)
          .withName("argocd-central")
          .withNamespace(ns)
          .withServiceAccountsFrom(
              config.getMultiTenant().getCentralArgocdNamespace(), ARGOCD_SERVICE_ACCOUNTS)
          .withConfig(config)
          .withRepo(repositoryWorkspace.getClusterResourcesRepository())
          .withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
          .generate();
    }
  }

  private void updateCentralManagedNamespaces() {
    String base64Namespaces =
        (String)
            k8sClient.getArgoCDNamespacesSecret(
                "argocd-default-cluster-config",
                config.getMultiTenant().getCentralArgocdNamespace());

    String decoded = "";
    if (base64Namespaces != null) {
      byte[] decodedBytes = Base64.getDecoder().decode(base64Namespaces);
      decoded = new String(decodedBytes, StandardCharsets.UTF_8);
    }

    List<String> decodedList =
        decoded.isEmpty() ? new ArrayList<>() : Arrays.asList(decoded.split(","));
    java.util.Collection<String> activeList =
        config.getApplication().getNamespaces().getActiveNamespaces();
    if (activeList == null) {
      activeList = new ArrayList<>();
    }

    List<String> mergedList = new ArrayList<>(decodedList);
    mergedList.addAll(activeList);
    String merged = mergedList.stream().distinct().collect(Collectors.joining(","));

    log.debug("Updating Central Argocd 'argocd-default-cluster-config' secret");

    k8sClient.patch(
        "secret",
        "argocd-default-cluster-config",
        config.getMultiTenant().getCentralArgocdNamespace(),
        Map.of("stringData", Map.of("namespaces", merged)));
  }

  private void createRepoCredentialsSecret(
      String secretName, String ns, String url, String username, String password) {
    k8sClient.createSecret(
        "generic",
        secretName,
        ns,
        new Tuple<>("url", url),
        new Tuple<>("username", username),
        new Tuple<>("password", password));

    k8sClient.label(
        "secret", secretName, ns, new Tuple<>("argocd.argoproj.io/secret-type", "repo-creds"));
  }
}
