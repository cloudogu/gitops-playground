package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.tools.common.AbstractTool;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Order(160)
@Slf4j
public class CertManager extends AbstractTool {

public static final String HELM_VALUES_PATH =
	"argocd/cluster-resources/apps/cert-manager/templates/values.ftl.yaml";

private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
private static final String TOOL_NAME = "cert-manager";
private static final String CERT_MANAGER_APP_PATH = "apps/cert-manager";

private final ImagePullSecretCreator imagePullSecretCreator;
private String namespace;

public CertManager(
	FileSystemUtils fileSystemUtils,
	Deployer deployer,
	AirGappedUtils airGappedUtils,
	GitHandler gitHandler,
	ImagePullSecretCreator imagePullSecretCreator) {
	this.fileSystemUtils = fileSystemUtils;
	this.deployer = deployer;
	this.airGappedUtils = airGappedUtils;
	this.gitHandler = gitHandler;
	this.imagePullSecretCreator = imagePullSecretCreator;
}

@Override
public boolean isEnabled(DeploymentContext context) {
	return context.getConfig().getFeatures().getCertManager().getActive();
}

@Override
protected void preDeploy() {
	this.namespace = activeNamespace(context);

	createImagePullSecret();
	prepareCertManagerApp(repositoryWorkspace.getClusterResourcesRepository());
	replaceCertManagerTemplates(repositoryWorkspace.getClusterResourcesRepository());
}

@Override
protected void deploy() {
	deployHelmChart(
		TOOL_NAME,
		TOOL_NAME,
		namespace,
		getConfig().getFeatures().getCertManager().getHelm(),
		HELM_VALUES_PATH,
		context);
}

@Override
protected void publishChanges() {
	publishClusterResourcesChanges(TOOL_NAME);
}

@Override
protected String activeNamespace(DeploymentContext context) {
	return context.getConfig().getApplication().getNamePrefix()
		+ context.getConfig().getFeatures().getCertManager().getNamespace();
}

@Override
public String getNamespace() {
	return namespace;
}

private void createImagePullSecret() {
	imagePullSecretCreator.createIfRequired(getConfig(), namespace);
}

private void prepareCertManagerApp(GitRepo clusterResourcesRepo) {
	log.debug(
		"Preparing cert-manager repository content in {}", clusterResourcesRepo.getRepoTarget());

	clusterResourcesRepo.copyDirectoryContents(
		CLUSTER_RESOURCES_SOURCE_DIR,
		ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, CERT_MANAGER_APP_PATH));
}

private void replaceCertManagerTemplates(GitRepo clusterResourcesRepo) {
	clusterResourcesRepo.replaceTemplates(Map.of("config", getConfig()));
}
}
