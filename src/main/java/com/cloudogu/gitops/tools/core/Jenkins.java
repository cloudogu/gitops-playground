package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.HelmConfigWithValues;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager;
import com.cloudogu.gitops.infrastructure.jenkins.JobManager;
import com.cloudogu.gitops.infrastructure.jenkins.PrometheusConfigurator;
import com.cloudogu.gitops.infrastructure.jenkins.UserManager;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.tools.common.Tool;
import com.cloudogu.gitops.utils.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Singleton
@Order(200)
@Slf4j
public class Jenkins extends Tool {

    public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/jenkins/templates/values.ftl.yaml";

    private static final List<String> OIDC_BOOT_PLUGIN_NAMES = Arrays.asList("oic-auth", "json-path-api");

    private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
    private static final String TOOL_NAME = "jenkins";
    private static final String JENKINS_APP_PATH = "apps/jenkins";
    private static final String RELEASE_NAME = "jenkins";

    @Getter
    @Setter
    private String namespace;
    private final CommandExecutor commandExecutor;
    private final GlobalPropertyManager globalPropertyManager;
    private final JobManager jobManager;
    private final UserManager userManager;
    private final PrometheusConfigurator prometheusConfigurator;

    private final ImagePullSecretCreator imagePullSecretCreator;
    private final K8sClient k8sClient;
    private final NetworkingUtils networkingUtils;

    public Jenkins(CommandExecutor commandExecutor,
                   FileSystemUtils fileSystemUtils,
                   GlobalPropertyManager globalPropertyManager,
                   JobManager jobManager,
                   UserManager userManager,
                   PrometheusConfigurator prometheusConfigurator,
                   Deployer deployer,
                   K8sClient k8sClient,
                   NetworkingUtils networkingUtils,
                   AirGappedUtils airGappedUtils,
                   GitHandler gitHandler,
                   ImagePullSecretCreator imagePullSecretCreator) {
        this.commandExecutor = commandExecutor;
        this.fileSystemUtils = fileSystemUtils;
        this.globalPropertyManager = globalPropertyManager;
        this.jobManager = jobManager;
        this.userManager = userManager;
        this.prometheusConfigurator = prometheusConfigurator;
        this.deployer = deployer;
        this.k8sClient = k8sClient;
        this.networkingUtils = networkingUtils;
        this.airGappedUtils = airGappedUtils;
        this.gitHandler = gitHandler;
        this.imagePullSecretCreator = imagePullSecretCreator;
    }

    @Override
    public boolean isEnabled(DeploymentContext context) {
        return context.getConfig().getJenkins().getActive();
    }

    @Override
    protected void preDeploy() {
        if (!isInternalJenkins()) {
            return;
        }

        this.namespace = activeNamespace(context);

        createImagePullSecret();
        createJenkinsNamespace();
        labelJenkinsNode();
        createJenkinsCredentialsSecret();
        prepareJenkinsHelmValues();
        prepareJenkinsApp(repositoryWorkspace.getClusterResourcesRepository());
    }

    @Override
    protected void deploy() {
        if (!isInternalJenkins()) {
            return;
        }

        deployInternalJenkins();
    }

    @Override
    protected void postDeploy() {
        if (isInternalJenkins()) {
            updateJenkinsUrl();
        }

        runSetupScript();
    }

    @Override
    protected void publishChanges() {
        if (!isInternalJenkins()) {
            return;
        }

        publishClusterResourcesChanges(TOOL_NAME);
    }

    private void createImagePullSecret() {
        imagePullSecretCreator.createIfRequired(getConfig(), namespace);
    }

    private void createJenkinsNamespace() {
        k8sClient.createNamespace(namespace);
    }

    private void labelJenkinsNode() {
        // Mark the first node for Jenkins and agents. See jenkins/values.ftl.yaml "agent.workingDir" for details.
        // Remove first in case new nodes were added.
        k8sClient.labelRemove("node", "--all", "", "node");

        String nodeName = k8sClient.waitForNode().replace("node/", "");
        k8sClient.label("node", nodeName, new Tuple<>("node", "jenkins"));
    }

    private void createJenkinsCredentialsSecret() {
        k8sClient.createSecret("generic",
                "jenkins-credentials",
                namespace,
                new Tuple<>("jenkins-admin-user", getConfig().getJenkins().getUsername()),
                new Tuple<>("jenkins-admin-password", getConfig().getJenkins().getPassword()));
    }

    private void prepareJenkinsHelmValues() {
        addHelmValuesData("dockerGid", findDockerGid());
        addHelmValuesData("jenkinsBootPlugins", jenkinsOidcConfigured() ? getJenkinsOidcBootPlugins() : Collections.emptyList());
    }

    @Override
    protected String activeNamespace(DeploymentContext context) {
        return context.getConfig().getJenkins().getInternal()
                ? context.getConfig().getApplication().getNamePrefix() + context.getConfig().getJenkins().getNamespace()
                : null;
    }

    private boolean isInternalJenkins() {
        return getConfig().getJenkins().getInternal();
    }

    private void deployInternalJenkins() {
        HelmConfigWithValues helmConfig = getConfig().getJenkins().getHelm();

        deployHelmChart(TOOL_NAME,
                RELEASE_NAME,
                namespace,
                helmConfig,
                HELM_VALUES_PATH,
                context,
                true);
    }

    private void updateJenkinsUrl() {
        // Defined here: https://github.com/jenkinsci/helm-charts/blob/jenkins-5.8.1/charts/jenkins/templates/_helpers.tpl#L46-L57
        String serviceName = RELEASE_NAME;

        // Update jenkins.url after it is deployed and ports are known.
        if (getConfig().getApplication().getRunningInsideK8s()) {
            log.debug("Setting jenkins url to k8s service, since installation is running inside k8s");
            getConfig().getJenkins().setUrl(networkingUtils.createUrl(serviceName + "." + namespace + ".svc.cluster.local", "80"));
        } else {
            log.debug("Setting jenkins configs for local single node cluster with internal jenkins. Waiting for NodePort...");
            String port = k8sClient.waitForNodePort(serviceName, namespace);
            String clusterBindAddress = networkingUtils.findClusterBindAddress();
            getConfig().getJenkins().setUrl(networkingUtils.createUrl(clusterBindAddress, port));
        }
    }

    private void prepareJenkinsApp(GitRepo clusterResourcesRepo) {
        log.debug("Preparing Jenkins repository content in {}", clusterResourcesRepo.getRepoTarget());

        clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
                ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, JENKINS_APP_PATH));
    }

    private void runSetupScript() {
        Map<String, Object> scriptParams = new HashMap<>();
        scriptParams.put("TRACE", getConfig().getApplication().getTrace());
        scriptParams.put("INTERNAL_JENKINS", getConfig().getJenkins().getInternal());
        scriptParams.put("JENKINS_HELM_CHART_VERSION", getConfig().getJenkins().getHelm().getVersion());
        scriptParams.put("JENKINS_URL", getConfig().getJenkins().getUrl());
        scriptParams.put("JENKINS_USERNAME", getConfig().getJenkins().getUsername());
        scriptParams.put("JENKINS_PASSWORD", getConfig().getJenkins().getPassword());
        scriptParams.put("SCM_URL", this.gitHandler.getTenant().getUrl());
        scriptParams.put("PREFIXED_SCM_URL", this.gitHandler.getTenant().repoPrefix());
        scriptParams.put("SCM_PASSWORD", this.gitHandler.getTenant().getCredentials().getPassword());
        scriptParams.put("SCM_PROVIDER", getConfig().getScm().getScmProviderType());
        scriptParams.put("INSTALL_ARGOCD", getConfig().getFeatures().getArgocd().getActive());
        scriptParams.put("NAME_PREFIX", getConfig().getApplication().getNamePrefix());
        scriptParams.put("INSECURE", getConfig().getApplication().getInsecure());
        scriptParams.put("SKIP_RESTART", getConfig().getJenkins().getSkipRestart());
        scriptParams.put("SKIP_PLUGINS", getConfig().getJenkins().getSkipPlugins());

        commandExecutor.execute(fileSystemUtils.getRootDir() + "/scripts/jenkins/init-jenkins.sh", scriptParams);

        globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "SCM_URL", this.gitHandler.getTenant().getUrl());
        globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "PREFIXED_SCM_URL", this.gitHandler.getTenant().repoPrefix());

        if (getConfig().getJenkins().getAdditionalEnvs() != null) {
            for (Map.Entry<String, String> entry : getConfig().getJenkins().getAdditionalEnvs().entrySet()) {
                globalPropertyManager.setGlobalProperty(entry.getKey(), entry.getValue());
            }
        }

        if (getConfig().getRegistry().getUrl() != null && !getConfig().getRegistry().getUrl().isEmpty()) {
            globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "REGISTRY_URL", getConfig().getRegistry().getUrl());
        }

        if (getConfig().getRegistry().getPath() != null && !getConfig().getRegistry().getPath().isEmpty()) {
            globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "REGISTRY_PATH", getConfig().getRegistry().getPath());
        }

        if (Boolean.TRUE.equals(getConfig().getRegistry().getTwoRegistries())) {
            globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "REGISTRY_PROXY_URL", getConfig().getRegistry().getProxyUrl());
            globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "REGISTRY_PROXY_PATH", getConfig().getRegistry().getProxyPath());
        }

        if (getConfig().getJenkins().getMavenCentralMirror() != null && !getConfig().getJenkins().getMavenCentralMirror().isEmpty()) {
            globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "MAVEN_CENTRAL_MIRROR", getConfig().getJenkins().getMavenCentralMirror());
        }

        globalPropertyManager.setGlobalProperty(getConfig().getApplication().getNamePrefixForEnvVars() + "K8S_VERSION", Config.K8S_VERSION);

        if (userManager.isUsingSecurityRealmWithoutLocalUserCreation()) {
            log.trace("Using a security realm without local user creation. Must not create user.");
        } else {
            userManager.createUser(getConfig().getJenkins().getMetricsUsername(), getConfig().getJenkins().getMetricsPassword());
        }

        userManager.grantPermission(getConfig().getJenkins().getMetricsUsername(), UserManager.Permissions.METRICS_VIEW);

        if (Boolean.TRUE.equals(getConfig().getFeatures().getMonitoring().getActive()) && Boolean.TRUE.equals(getConfig().getJenkins().getInternal())) {
            // An external Jenkins can likely not be monitored
            prometheusConfigurator.enableAuthentication();
        }
    }

    public void createJenkinsjob(String namespace, String repoName) {
        String credentialId = "scm-user";
        String prefixedNamespace = getConfig().getApplication().getNamePrefix() + namespace;
        String jobName = getConfig().getApplication().getNamePrefix() + repoName;

        jobManager.createJob(jobName,
                this.gitHandler.getTenant().getUrl(),
                prefixedNamespace,
                credentialId);

        if (getConfig().getScm().getScmProviderType() == ScmProviderType.SCM_MANAGER) {
            jobManager.createCredential(jobName,
                    credentialId,
                    getConfig().getApplication().getNamePrefix() + "gitops",
                    getConfig().getScm().getScmManager().getPassword(),
                    "credentials for accessing scm-manager");
        }

        if (getConfig().getScm().getScmProviderType() == ScmProviderType.GITLAB) {
            jobManager.createCredential(jobName,
                    credentialId,
                    getConfig().getScm().getGitlab().getUsername(),
                    getConfig().getScm().getGitlab().getPassword(),
                    "credentials for accessing gitlab");
        }

        jobManager.createCredential(jobName,
                "registry-user",
                getConfig().getRegistry().getUsername(),
                getConfig().getRegistry().getPassword(),
                "credentials for accessing the docker-registry for writing images built on jenkins");

        if (Boolean.TRUE.equals(getConfig().getRegistry().getTwoRegistries())) {
            jobManager.createCredential(jobName,
                    "registry-proxy-user",
                    getConfig().getRegistry().getProxyUsername(),
                    getConfig().getRegistry().getProxyPassword(),
                    "credentials for accessing the docker-registry that contains 3rd party or base images");
        }

        jobManager.startJob(jobName);
    }

    private boolean jenkinsOidcConfigured() {
        return getConfig().getJenkins().getOidc() != null && !getConfig().getJenkins().getOidc().trim().isEmpty();
    }

    private List<String> getJenkinsOidcBootPlugins() {
        File pluginsFile = new File(fileSystemUtils.getRootDir() + "/scripts/jenkins/plugins/plugins.txt");
        Map<String, String> pinnedPlugins = new HashMap<>();

        try {
            List<String> lines = Files.readAllLines(pluginsFile.toPath());
            for (String line : lines) {
                String pluginDefinition = line.trim();
                if (!pluginDefinition.isEmpty() && !pluginDefinition.startsWith("#")) {
                    String pluginName = pluginDefinition.split(":", 2)[0];
                    if (OIDC_BOOT_PLUGIN_NAMES.contains(pluginName)) {
                        pinnedPlugins.put(pluginName, pluginDefinition);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read plugins file: " + pluginsFile, e);
        }

        List<String> missingPlugins = OIDC_BOOT_PLUGIN_NAMES.stream()
                .filter(name -> !pinnedPlugins.containsKey(name))
                .toList();

        if (!missingPlugins.isEmpty()) {
            throw new IllegalStateException("Required Jenkins OIDC boot plugins missing from " + pluginsFile + ": " + String.join(", ", missingPlugins));
        }

        List<String> result = new ArrayList<>();
        for (String name : OIDC_BOOT_PLUGIN_NAMES) {
            result.add(pinnedPlugins.get(name));
        }
        return result;
    }

    protected String findDockerGid() {
        String gid = "";
        String etcGroup = k8sClient.run("tmp-docker-gid-grepper-" + new Random().nextInt(10000),
                "irrelevant" /* Redundant, but mandatory param */, namespace, createGidGrepperOverrides(),
                "--restart=Never", "-ti", "--rm", "--quiet");

        if (etcGroup != null) {
            String[] lines = etcGroup.split("\n");
            for (String line : lines) {
                String[] parts = line.split(":");
                if (parts.length >= 3 && "docker".equals(parts[0])) {
                    gid = parts[2];
                    break;
                }
            }
        }

        if (gid.isEmpty()) {
            log.warn("Unable to determine Docker Group ID (GID). Jenkins Agent pods will run as root user (UID 0)!\n" +
                    "Group docker not found in /etc/group:\n{}", etcGroup);
            return "";
        } else {
            log.debug("Using Docker Group ID (GID) {} for Jenkins Agent pods", gid);
            return gid;
        }
    }

    Map<String, Object> createGidGrepperOverrides() {
        return Map.of("spec", Map.of(
                "containers", List.of(Map.of(
                        "name", "tmp-docker-gid-grepper",
                        "image", getConfig().getJenkins().getInternalBashImage(),
                        "args", List.of("cat", "/etc/group"),
                        "volumeMounts", List.of(Map.of(
                                "name", "group",
                                "mountPath", "/etc/group",
                                "readOnly", true
                        ))
                )),
                "nodeSelector", Map.of("node", "jenkins"),
                "volumes", List.of(Map.of(
                        "name", "group",
                        "hostPath", Map.of("path", "/etc/group")
                ))
        ));
    }

    @Override
    public String getActiveNamespaceFromFeature(DeploymentContext context) {
        return isEnabled(context) ? activeNamespace(context) : null;
    }
}
