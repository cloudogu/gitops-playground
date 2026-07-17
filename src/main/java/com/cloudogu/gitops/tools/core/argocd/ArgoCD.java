package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.Tool;
import com.cloudogu.gitops.tools.core.argocd.mode.DeploymentMode;
import com.cloudogu.gitops.tools.core.argocd.mode.DeploymentModeFactory;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.Tuple;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
@Order(100)
public class ArgoCD extends Tool {

    private static final Logger log = LoggerFactory.getLogger(ArgoCD.class);

    private final K8sClient k8sClient;
    private final HelmClient helmClient;
    private final DeploymentModeFactory deploymentModeFactory;

    private String password;
    private String namespace;
    private ArgoCDRepoSetup repoSetup;
    private ArgoCDRepoLayout clusterResourcesRepo;
    private DeploymentMode deploymentMode;

    public ArgoCD(K8sClient k8sClient,
                  HelmClient helmClient,
                  FileSystemUtils fileSystemUtils,
                  GitHandler gitHandler,
                  DeploymentModeFactory deploymentModeFactory) {
        this.k8sClient = k8sClient;
        this.helmClient = helmClient;
        this.fileSystemUtils = fileSystemUtils;
        this.gitHandler = gitHandler;
        this.deploymentModeFactory = deploymentModeFactory;
    }

    @Override
    public boolean isEnabled(DeploymentContext context) {
        return context.getConfig().getFeatures().getArgocd().getActive();
    }

    @Override
    protected void preDeploy() {
        this.namespace = activeNamespace(context);
        this.password = getConfig().getApplication().getPassword();

        this.repoSetup = ArgoCDRepoSetup.create(context,
                fileSystemUtils,
                gitHandler,
                repositoryWorkspace);

        this.clusterResourcesRepo = repoSetup.clusterRepoLayout();

        this.deploymentMode = deploymentModeFactory.create(context,
                getConfig(),
                k8sClient,
                gitHandler,
                repositoryWorkspace,
                repoSetup,
                clusterResourcesRepo,
                namespace);

        log.debug("Preparing ArgoCD repository content");
        repoSetup.prepareRepositories();

        log.debug("Creating namespaces");
        k8sClient.createNamespaces(new ArrayList<>(getConfig().getApplication().getNamespaces().getActiveNamespaces()));

        deploymentMode.createSCMCredentialsSecret();
        createNotificationSecretIfRequired();

        if (getConfig().getFeatures().getArgocd().getOperator()) {
            deploymentMode.generateRBAC();
        } else {
            mergeHelmValuesIfConfigured();
        }
    }

    @Override
    protected void deploy() {
        log.debug("Installing Argo CD");

        if (getConfig().getFeatures().getArgocd().getOperator()) {
            deployWithOperator();
        } else {
            deployWithHelm();
        }
    }

    @Override
    protected void postDeploy() {
        deploymentMode.applyBootstrapResources();
        deleteHelmArgoSecrets();
    }

    @Override
    protected void publishChanges() {
        try {
            repositoryWorkspace.commitAndPushClusterResourcesAndTenantBootstrapChanges("Update ArgoCD repository content");
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish ArgoCD changes", e);
        }
    }

    @Override
    protected String activeNamespace(DeploymentContext context) {
        return context.getConfig().getApplication().getNamePrefix() + context.getConfig().getFeatures().getArgocd().getNamespace();
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public void postConfigInit(Config configToSet) {
        // Exit early if not in operator mode or if env list is empty
        if (!configToSet.getFeatures().getArgocd().getOperator() || configToSet.getFeatures().getArgocd().getEnv() == null) {
            log.debug("Skipping features.argocd.env validation: operator mode is disabled or env list is empty.");
            return;
        }

        List env = configToSet.getFeatures().getArgocd().getEnv();

        log.info("Validating env list in features.argocd.env with {} entries.", env.size());

        for (Object entry : env) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: " + entry);
            }
            Map<String, String> map = (Map<String, String>) entry;
            if (!map.containsKey("name") || !map.containsKey("value")) {
                throw new IllegalArgumentException("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: " + formatMapLikeGroovy(map));
            }
        }

        log.info("Env list validation for features.argocd.env completed successfully.");
    }

    private static String formatMapLikeGroovy(Map<String, String> map) {
        if (map == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(":").append(entry.getValue());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private void createNotificationSecretIfRequired() {
        String smtpUser = getConfig().getFeatures().getMail().getSmtpUser();
        String smtpPassword = getConfig().getFeatures().getMail().getSmtpPassword();
        if ((smtpUser != null && !smtpUser.isEmpty()) || (smtpPassword != null && !smtpPassword.isEmpty())) {
            k8sClient.createSecret("generic",
                    "argocd-notifications-secret",
                    namespace,
                    new Tuple<>("email-username", smtpUser),
                    new Tuple<>("email-password", smtpPassword));
        }
    }

    private void mergeHelmValuesIfConfigured() {
        Map<String, Object> values = getConfig().getFeatures().getArgocd().getValues();
        if (values == null || values.isEmpty()) {
            return;
        }

        String argocdConfigPath = clusterResourcesRepo.helmValuesFile();
        log.debug("extend Argocd values.yaml with {}", values);

        Map<String, Object> argocdYaml = fileSystemUtils.readYaml(Path.of(argocdConfigPath));
        Map<String, Object> result = MapUtils.deepMerge(values, argocdYaml);

        fileSystemUtils.writeYaml(result, new File(argocdConfigPath));
        log.debug("Argocd values.yaml contains {}", result);
    }

    private void deleteHelmArgoSecrets() {
        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm.
        // For development keeping it in helm makes it easier, e.g. for helm uninstall.
        k8sClient.delete("secret",
                namespace,
                new Tuple<>("owner", "helm"),
                new Tuple<>("name", "argocd"));
    }

    private void deployWithOperator() {
        String argocdConfigPath = clusterResourcesRepo.operatorConfigFile();
        Map<String, Object> values = getConfig().getFeatures().getArgocd().getValues();

        if (values != null && !values.isEmpty()) {
            log.debug("extend Argocd.yaml with {}", values);

            Map<String, Object> argocdYaml = fileSystemUtils.readYaml(Path.of(clusterResourcesRepo.operatorConfigFile()));
            Map<String, Object> result = MapUtils.deepMerge(values, argocdYaml);

            fileSystemUtils.writeYaml(result, new File(argocdConfigPath));
            log.debug("Argocd.yaml for operator contains {}", result);

            argocdConfigPath = clusterResourcesRepo.operatorConfigFile();
        }

        k8sClient.applyYaml(argocdConfigPath);

        // ArgoCD is not installed until the ArgoCD-Operator did his job.
        // This can take some time, so we wait for the status of the custom resource to become "Available"
        k8sClient.waitForResourcePhase("argocd", "argocd", namespace, "Available");

        updateAdminPasswordForOperator();

        deploymentMode.updateManagedNamespaces();

        log.debug("Apply RBAC permissions for ArgoCD in all managed namespaces imperatively");
        k8sClient.applyYaml(clusterResourcesRepo.operatorRbacDir());
    }

    private void updateAdminPasswordForOperator() {
        log.debug("Setting new argocd admin password");

        // Set admin password imperatively here instead of operator/argocd.yaml, because we don't want it to show in git repo.
        // The Operator uses an extra secret to store the admin Password, which is not bcrypted.
        k8sClient.patch("secret", "argocd-cluster", namespace,
                Map.of("stringData", Map.of("admin.password", password)));

        // In newer Versions ArgoCD Operator uses the password in argocd-cluster secret only as generated initial password,
        // but we want to set our own admin password so we set the password in both Secrets for consistency.
        updateBcryptAdminPassword();
    }

    private void deployWithHelm() {
        String umbrellaChartPath = clusterResourcesRepo.helmDir();

        // Even if the Chart.lock already contains the repo, we need to add it before resolving it.
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        Map<String, Object> chartYaml = fileSystemUtils.readYaml(Path.of(clusterResourcesRepo.chartYaml()));
        List<Map<String, Object>> helmDependencies = (List<Map<String, Object>>) chartYaml.get("dependencies");
        String repository = (String) helmDependencies.get(0).get("repository");

        helmClient.addRepo("argo", repository);
        helmClient.dependencyBuild(umbrellaChartPath);
        helmClient.upgrade("argocd", umbrellaChartPath, Map.of("namespace", namespace));

        updateBcryptAdminPassword();
    }

    private void updateBcryptAdminPassword() {
        log.debug("Setting new argocd admin password");

        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4));

        k8sClient.patch("secret",
                "argocd-secret",
                namespace,
                Map.of("stringData", Map.of("admin.password", bcryptArgoCDPassword)));
    }

    protected ArgoCDRepoSetup getRepoSetup() {
        return this.repoSetup;
    }
}
