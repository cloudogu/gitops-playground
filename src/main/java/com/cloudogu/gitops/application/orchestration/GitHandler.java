package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.gitlab.GitlabProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class GitHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHandler.class);

    private NetworkingUtils networkingUtils;
    private K8sClient k8sClient;

    private GitProvider tenant;
    private GitProvider central;

    public GitHandler(K8sClient k8sClient, NetworkingUtils networkingUtils) {
        this.k8sClient = k8sClient;
        this.networkingUtils = networkingUtils;
    }

    public void validate(DeploymentContext context) {
        Config config = context.getConfig();

        if (config.getScm().getGitlab() != null && !isNullOrEmpty(config.getScm().getGitlab().getUrl())) {
            config.getScm().setScmProviderType(ScmProviderType.GITLAB);
            config.getScm().setScmManager(null);

            if (isNullOrEmpty(config.getScm().getGitlab().getPassword()) || isNullOrEmpty(config.getScm().getGitlab().getParentGroupId())) {
                throw new RuntimeException("GitLab configuration incomplete: please provide both password (PAT) and parentGroupId");
            }
            return;
        }

        config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
        if (config.getScm().getScmManager() != null) {
            String prefix = config.getApplication().getNamePrefix();
            if (prefix == null) {
                prefix = "";
            }
            config.getScm().getScmManager().setGitOpsUsername(prefix + "gitops");
        }
    }

    public void prepareProviders(DeploymentContext context) {
        this.tenant = createTenantScmProvider(context);

        if (context.isMultiTenant()) {
            this.central = createCentralScmProvider(context);
        }
    }

    public GitProvider getResourcesScm() {
        if (central != null) {
            return central;
        }

        if (tenant != null) {
            return tenant;
        }

        throw new IllegalStateException("No SCM provider found.");
    }

    private GitProvider createTenantScmProvider(DeploymentContext context) {
        Config config = context.getConfig();

        switch (config.getScm().getScmProviderType()) {
            case GITLAB:
                return new GitlabProvider(context, config.getScm().getGitlab());
            case SCM_MANAGER:
                String prefix = config.getApplication().getNamePrefix();
                if (prefix == null) {
                    prefix = "";
                }
                return new ScmManagerProvider(context,
                        config.getScm().getScmManager(),
                        k8sClient,
                        networkingUtils,
                        prefix);

            default:
                throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM: " + config.getScm().getScmProviderType());
        }
    }

    private GitProvider createCentralScmProvider(DeploymentContext context) {
        Config config = context.getConfig();

        switch (config.getMultiTenant().getScmProviderType()) {
            case GITLAB:
                return new GitlabProvider(context, config.getMultiTenant().getGitlab());
            case SCM_MANAGER:
                return new ScmManagerProvider(context,
                        config.getMultiTenant().getScmManager(),
                        k8sClient,
                        networkingUtils,
                        centralScmManagerServicePrefix(config));

            default:
                throw new IllegalArgumentException("Unsupported SCM-Central provider: " + config.getMultiTenant().getScmProviderType());
        }
    }

    private String centralScmManagerServicePrefix(Config config) {
        String namespace = config.getMultiTenant().getScmManager().getNamespace();
        if (namespace == null) {
            namespace = "";
        }
        namespace = namespace.strip();
        String baseNamespace = "scm-manager";

        if (namespace.equals(baseNamespace) || !namespace.endsWith(baseNamespace)) {
            return "";
        }

        return namespace.substring(0, namespace.length() - baseNamespace.length());
    }

    private boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    // Getters and Setters

    public NetworkingUtils getNetworkingUtils() {
        return networkingUtils;
    }

    public void setNetworkingUtils(NetworkingUtils networkingUtils) {
        this.networkingUtils = networkingUtils;
    }

    public K8sClient getK8sClient() {
        return k8sClient;
    }

    public void setK8sClient(K8sClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    public GitProvider getTenant() {
        return tenant;
    }

    public void setTenant(GitProvider tenant) {
        this.tenant = tenant;
    }

    public GitProvider getCentral() {
        return central;
    }

    public void setCentral(GitProvider central) {
        this.central = central;
    }
}
