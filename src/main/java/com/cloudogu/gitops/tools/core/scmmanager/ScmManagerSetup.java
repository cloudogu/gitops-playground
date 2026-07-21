package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerUser;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.TemplateModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class ScmManagerSetup {

  private static final String HELM_VALUES_PATH =
      "argocd/cluster-resources/apps/scm-manager/templates/values.ftl.yaml";

  private final ScmManagerProvider scmManager;
  private final Deployer deployer;
  private final DeploymentContext context;
  private final RepositoryWorkspace repositoryWorkspace;

  private Path tempValuesPath;

  private Config getConfig() {
    return context.getConfig();
  }

  public void setupHelm() {
    Path valuesPath = prepareHelmValues();
    Config.HelmConfigWithValues helmConfig = this.scmManager.getScmmConfig().getHelm();
    String releaseName = scmmReleaseName();

    log.info(
        "Deploying SCM-Manager via Helm with releaseName='{}', namespace='{}', namePrefix='{}', dedicatedInstance={}",
        releaseName,
        this.scmManager.getScmmConfig().getNamespace(),
        getConfig().getApplication().getNamePrefix(),
        context.isMultiTenant());

    deployer
        .getHelmStrategy()
        .deployFeature(
            helmConfig.getRepoURL(),
            "scm-manager",
            helmConfig.getChart(),
            helmConfig.getVersion(),
            this.scmManager.getScmmConfig().getNamespace(),
            releaseName,
            valuesPath,
            DeploymentStrategy.RepoType.HELM);
  }

  public void createArgocdApplication() {
    Path valuesPath = tempValuesPath != null ? tempValuesPath : prepareHelmValues();
    Config.HelmConfigWithValues helmConfig = this.scmManager.getScmmConfig().getHelm();
    String releaseName = scmmReleaseName();

    log.info(
        "Creating SCM-Manager ArgoCD application with releaseName='{}', namespace='{}', namePrefix='{}', dedicatedInstance={}",
        releaseName,
        this.scmManager.getScmmConfig().getNamespace(),
        getConfig().getApplication().getNamePrefix(),
        context.isMultiTenant());

    deployer.deployFeature(
        helmConfig.getRepoURL(),
        "scm-manager",
        helmConfig.getChart(),
        helmConfig.getVersion(),
        this.scmManager.getScmmConfig().getNamespace(),
        releaseName,
        valuesPath,
        DeploymentStrategy.RepoType.HELM,
        false,
        context,
        repositoryWorkspace);
  }

  public void prepareBootstrapRepositoriesAfterScmManagerDeployment() {
    try {
      repositoryWorkspace.ensureRemoteRepositoriesExist();
      repositoryWorkspace.initLocalRepositoriesIfNeeded();
      repositoryWorkspace.alignWithRemoteMainIfPresent();
      repositoryWorkspace.createLocalDirectories();
    } catch (Exception e) {
      throw new RuntimeException("Failed to prepare bootstrap repositories", e);
    }
  }

  public void pushBootstrapRepositoriesAfterScmManagerDeployment() {
    try {
      repositoryWorkspace.commitAndPushClusterResourcesChanges(
          "Bootstrap cluster-resources repository after SCM-Manager deployment");

      if (repositoryWorkspace.hasTenantBootstrapRepository()) {
        repositoryWorkspace.commitAndPushTenantBootstrapChanges(
            "Bootstrap tenant repository after SCM-Manager deployment");
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to push bootstrap repositories", e);
    }
  }

  private Path prepareHelmValues() {
    String releaseName = scmmReleaseName();

    log.debug(
        "Preparing SCM-Manager Helm values with releaseName='{}', namespace='{}'",
        releaseName,
        this.scmManager.getScmmConfig().getNamespace());

    Map<String, Object> templateVars = new HashMap<>();
    templateVars.put("config", this.scmManager.getConfig());
    templateVars.put("host", this.scmManager.getScmmConfig().getIngress());
    templateVars.put("username", this.scmManager.getScmmConfig().getCredentials().getUsername());
    templateVars.put("password", this.scmManager.getScmmConfig().getCredentials().getPassword());
    templateVars.put("helm", this.scmManager.getScmmConfig().getHelm());
    templateVars.put("releaseName", releaseName);

    try {
      TemplateModel statics =
          new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels();
      templateVars.put("statics", statics);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    Map<String, Object> templatedMap =
        TemplatingEngine.templateToMap(HELM_VALUES_PATH, templateVars);
    Map<String, Object> values =
        this.scmManager.getScmmConfig().getHelm().getValues() != null
            ? this.scmManager.getScmmConfig().getHelm().getValues()
            : new HashMap<>();

    Map<String, Object> mergedMap = MapUtils.deepMerge(values, templatedMap);
    tempValuesPath = new FileSystemUtils().writeTempFile(mergedMap);

    return tempValuesPath;
  }

  private String scmmReleaseName() {
    String prefix =
        getConfig().getApplication().getNamePrefix() != null
            ? getConfig().getApplication().getNamePrefix().strip()
            : "";

    if (!prefix.isEmpty()) {
      return prefix + "scmm";
    }

    return "scmm";
  }

  public void waitForScmmAvailable() {
    waitForScmmAvailable(180, 5000, 0);
  }

  public void waitForScmmAvailable(int timeoutSeconds, int intervalMillis, int startDelay) {
    long startTime = System.currentTimeMillis();
    long timeoutMillis = timeoutSeconds * 1000L;

    if (startDelay > 0) {
      try {
        Thread.sleep(startDelay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    while (System.currentTimeMillis() - startTime < timeoutMillis) {
      try {
        retrofit2.Call<Void> call = scmManager.getApiClient().generalApi().checkScmmAvailable();
        retrofit2.Response<Void> response = call.execute();

        if (response.isSuccessful()) {
          log.debug("SCM-Manager is available.");
          return;
        }
      } catch (Exception e) {
        log.debug("Waiting for SCM-Manager... Error: {}", e.getMessage());
      }

      try {
        Thread.sleep(intervalMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    throw new RuntimeException(
        "Timeout: SCM-Manager did not respond with 200 OK within " + timeoutSeconds + " seconds");
  }

  public void configure() {
    installScmmPlugins();
    setSetupConfigs();

    if (this.scmManager.getConfig().getJenkins().getActive()) {
      configureJenkinsPlugin();
    }

    addDefaultUsers();

    log.info("ScmManager Setup finished!");
  }

  private void installScmmPlugins() {
    if (this.scmManager.getConfig().getScm().getScmManager().getSkipPlugins()) {
      log.debug("Skipping SCM plugin installation");
      return;
    }

    List<String> pluginNames =
        new ArrayList<>(
            List.of(
                "scm-mail-plugin",
                "scm-review-plugin",
                "scm-code-editor-plugin",
                "scm-editor-plugin",
                "scm-landingpage-plugin",
                "scm-el-plugin",
                "scm-readme-plugin",
                "scm-webhook-plugin",
                "scm-ci-plugin",
                "scm-metrics-prometheus-plugin"));

    if (this.scmManager.getConfig().getJenkins().getActive()) {
      pluginNames.add("scm-jenkins-plugin");
    }

    boolean restartForThisPlugin = false;

    for (int i = 0; i < pluginNames.size(); i++) {
      String pluginName = pluginNames.get(i);
      log.debug("Installing Plugin {} ...", pluginName);

      restartForThisPlugin =
          !this.scmManager.getConfig().getScm().getScmManager().getSkipRestart()
              && i == pluginNames.size() - 1;

      ScmManagerApiClient.handleApiResponse(
          scmManager.getApiClient().pluginApi().install(pluginName, restartForThisPlugin));
    }

    log.debug("SCM-Manager plugin installation finished successfully!");

    if (restartForThisPlugin) {
      waitForScmmAvailable(180, 2000, 100);
    }
  }

  private void setSetupConfigs() {
    Map<String, Object> setupConfigs = new HashMap<>();
    setupConfigs.put("enableProxy", false);
    setupConfigs.put("proxyPort", 8080);
    setupConfigs.put("proxyServer", "proxy.mydomain.com");
    setupConfigs.put("proxyUser", null);
    setupConfigs.put("proxyPassword", null);
    setupConfigs.put("realmDescription", "SONIA :: SCM Manager");
    setupConfigs.put("disableGroupingGrid", false);
    setupConfigs.put("dateFormat", "YYYY-MM-DD HH:mm:ss");
    setupConfigs.put("anonymousAccessEnabled", false);
    setupConfigs.put("anonymousMode", "OFF");
    setupConfigs.put("baseUrl", this.scmManager.getUrl());
    setupConfigs.put("forceBaseUrl", false);
    setupConfigs.put("loginAttemptLimit", -1);
    setupConfigs.put("proxyExcludes", new ArrayList<>());
    setupConfigs.put("skipFailedAuthenticators", false);
    setupConfigs.put(
        "pluginUrl",
        "https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}");
    setupConfigs.put("loginAttemptLimitTimeout", 300);
    setupConfigs.put("enabledXsrfProtection", true);
    setupConfigs.put("namespaceStrategy", "CustomNamespaceStrategy");
    setupConfigs.put("loginInfoUrl", "https://login-info.scm-manager.org/api/v1/login-info");
    setupConfigs.put("releaseFeedUrl", "https://scm-manager.org/download/rss.xml");
    setupConfigs.put("mailDomainName", "scm-manager.local");
    setupConfigs.put("adminGroups", new ArrayList<>());
    setupConfigs.put("adminUsers", new ArrayList<>());

    ScmManagerApiClient.handleApiResponse(
        scmManager.getApiClient().generalApi().setConfig(setupConfigs));

    log.debug("Successfully added SCMM Setup Configs");
  }

  private void configureJenkinsPlugin() {
    Map<String, Object> jenkinsPluginConfig = new HashMap<>();
    jenkinsPluginConfig.put("disableRepositoryConfiguration", false);
    jenkinsPluginConfig.put("disableMercurialTrigger", false);
    jenkinsPluginConfig.put("disableGitTrigger", false);
    jenkinsPluginConfig.put("disableEventTrigger", false);
    jenkinsPluginConfig.put("url", this.scmManager.getConfig().getJenkins().getUrlForScm());

    ScmManagerApiClient.handleApiResponse(
        this.scmManager.getApiClient().pluginApi().configureJenkinsPlugin(jenkinsPluginConfig));

    log.debug("Successfully configured JenkinsPlugin in SCM-Manager.");
  }

  private void addDefaultUsers() {
    String metricsUsername =
        this.scmManager.getConfig().getApplication().getNamePrefix() + "metrics";

    addUser(
        this.scmManager.getScmmConfig().getGitOpsUsername(),
        this.scmManager.getScmmConfig().getPassword(),
        "changeme@test.local");
    addUser(metricsUsername, this.scmManager.getScmmConfig().getPassword(), "changeme@test.local");
    grantUserPermissions(metricsUsername, List.of("metrics:read"));
  }

  private void addUser(String username, String password, String email) {
    ScmManagerUser userRequest = new ScmManagerUser();
    userRequest.setName(username);
    userRequest.setDisplayName(username);
    userRequest.setMail(email);
    userRequest.setExternal(false);
    userRequest.setPassword(password);
    userRequest.setActive(true);
    userRequest.set_links(new HashMap<>());

    ScmManagerApiClient.handleApiResponse(
        scmManager.getApiClient().usersApi().addUser(userRequest));

    log.debug("Successfully created SCM-Manager User {}.", username);
  }

  private void grantUserPermissions(String username, List<String> permissions) {
    Map<String, List<String>> permissionBody = new HashMap<>();
    permissionBody.put("permissions", permissions);

    ScmManagerApiClient.handleApiResponse(
        scmManager.getApiClient().usersApi().setPermissionForUser(username, permissionBody));

    log.debug("Granted permissions {} to user {}.", permissions, username);
  }
}
