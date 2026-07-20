package com.cloudogu.gitops.tools.common;

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.HelmConfigWithValues;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import lombok.extern.slf4j.Slf4j;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public abstract class Tool {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
    private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};

    protected FileSystemUtils fileSystemUtils;
    protected Deployer deployer;
    protected AirGappedUtils airGappedUtils;
    protected GitHandler gitHandler;
    protected DeploymentContext context;
    protected RepositoryWorkspace repositoryWorkspace;
    protected Map<String, Object> helmValuesTemplateData = new HashMap<>();

    /**
     * Activation check for the current deployment run.
     *
     * This method must be side-effect free.
     * Do not add deployment preparation, config mutation or workspace access here.
     */
    public abstract boolean isEnabled(DeploymentContext context);

    /**
     * Executes this tool along its internal lifecycle.
     */
    public boolean execute(DeploymentContext context, RepositoryWorkspace workspace) {
        prepareExecution(context, workspace);

        log.info("Installing Tool {}", getClass().getSimpleName());

        validate();
        preDeploy();
        deploy();
        postDeploy();
        publishChanges();

        log.info("Tool installed: {}", getClass().getSimpleName());
        return true;
    }

    /**
     * Technical initialization of runtime state.
     *
     * This is not a lifecycle phase. Tool-specific preparation belongs into preDeploy().
     */
    protected void prepareExecution(DeploymentContext context, RepositoryWorkspace workspace) {
        this.context = context;
        this.repositoryWorkspace = workspace;
        this.helmValuesTemplateData = new HashMap<>();
    }

    /**
     * Lifecycle phase: validate tool-specific configuration and prerequisites.
     *
     * Throw a RuntimeException to stop the deployment immediately.
     */
    public void validate() {}

    /**
     * Lifecycle phase: prepare deployment inputs and prerequisites.
     */
    protected void preDeploy() {}

    /**
     * Lifecycle phase: deploy the tool.
     */
    protected void deploy() {}

    /**
     * Lifecycle phase: run follow-up steps after deployment.
     */
    protected void postDeploy() {}

    /**
     * Lifecycle phase: publish GitOps repository changes.
     */
    protected void publishChanges() {}

    protected void publishClusterResourcesChanges(String toolName) {
        try {
            repositoryWorkspace.commitAndPushClusterResourcesChanges("Update " + toolName + " GitOps resources");
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish cluster resources changes for " + toolName, e);
        }
    }

    protected void addHelmValuesData(String key, Object value) {
        this.helmValuesTemplateData.put(key, value);
    }

    public String getNamespace() {
        return null;
    }

    protected String activeNamespace(DeploymentContext context) {
        return null;
    }

    public String getActiveNamespaceFromFeature(DeploymentContext context) {
        return isEnabled(context) ? activeNamespace(context) : null;
    }

    public static Map<String, Object> templateToMap(String filePath, Map<String, Object> parameters) {
        try {
            String hydratedString = new TemplatingEngine().template(new File(filePath), parameters);

            if (hydratedString == null || hydratedString.trim().isEmpty()) {
                // Otherwise empty array or exception, whereas we expect a Map
                return Collections.emptyMap();
            }
            return yamlMapper.readValue(hydratedString, YAML_MAP_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to template file to map: " + filePath, e);
        }
    }

    protected void deployHelmChart(String featureName,
                                   String releaseName,
                                   String namespace,
                                   HelmConfigWithValues helmConfig,
                                   String helmValuesTemplatePath,
                                   DeploymentContext context) {
        deployHelmChart(featureName, releaseName, namespace, helmConfig, helmValuesTemplatePath, context, false);
    }

    protected void deployHelmChart(String featureName,
                                   String releaseName,
                                   String namespace,
                                   HelmConfigWithValues helmConfig,
                                   String helmValuesTemplatePath,
                                   DeploymentContext context,
                                   boolean initByHelm) {
        Config config = context.getConfig();
        String repoURL = helmConfig.getRepoURL();
        String chartOrPath = helmConfig.getChart();
        String version = helmConfig.getVersion();
        RepoType repoType = RepoType.HELM;

        this.addHelmValuesData("config", config);
        try {
            this.addHelmValuesData("statics", new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve Freemarker static models for template mapping", e);
        }

        /*
         * If we get a helmValuesTemplatePath we render the Template with the given Data.
         * Some Features might not use a values template and thus passing no helmValuesTemplatePath,
         * in that case we simply treat helmValuesTemplateData directly as helmValuesData.
         */
        Map<String, Object> helmValuesData = this.helmValuesTemplateData;
        if (helmValuesTemplatePath != null && !helmValuesTemplatePath.isEmpty()) {
            String helmValuesPath = helmValuesTemplatePath.toString();
            if (helmValuesPath.contains(".ftl")) {
                log.debug("Rendering helm values template from {}", helmValuesTemplatePath);
                helmValuesData = templateToMap(helmValuesTemplatePath, this.helmValuesTemplateData);
            } else {
                log.debug("Reading plain helm values YAML from {}", helmValuesTemplatePath);
                helmValuesData = fileSystemUtils.readYaml(Path.of(helmValuesTemplatePath));
            }
        }

        helmValuesData = MapUtils.deepMerge(helmConfig.getValues(), helmValuesData);
        Path tempValuesPath = this.fileSystemUtils.writeTempFile(helmValuesData);

        if (context.isAirgapped()) {
            log.debug("Using a local, mirrored git repo as deployment source for feature {}", featureName);

            String repoNamespaceAndName = this.airGappedUtils.mirrorHelmRepoToGit(helmConfig);
            repoURL = this.gitHandler.getResourcesScm().repoUrl(repoNamespaceAndName);
            chartOrPath = ".";
            repoType = RepoType.GIT;
            try {
                Map<String, Object> chartYaml = yamlMapper.readValue(Path.of(config.getApplication().getLocalHelmChartFolder(), helmConfig.getChart(), "Chart.yaml").toFile(), YAML_MAP_TYPE);
                version = String.valueOf(chartYaml.get("version"));
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse Chart.yaml for airgapped version mapping", e);
            }
        }

        log.debug("Starting deployment of feature {} from {}.", featureName, repoURL);
        log.debug("helm values used: {}", helmValuesData);

        this.deployer.deployFeature(repoURL,
                featureName,
                chartOrPath,
                version,
                namespace,
                releaseName,
                tempValuesPath,
                repoType,
                initByHelm,
                context,
                repositoryWorkspace);
    }

    public Config getConfig() {
        return context.getConfig();
    }

    public DeploymentContext getContext() {
        return context;
    }

    /**
     * Hook for preConfigInit. Optional.
     */
    public void preConfigInit(Config configToSet) {}

    /**
     * Hook for postConfigInit. Optional.
     */
    public void postConfigInit(Config configToSet) {}
}
