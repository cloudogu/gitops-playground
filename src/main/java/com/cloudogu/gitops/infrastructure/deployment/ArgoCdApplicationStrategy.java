package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class ArgoCdApplicationStrategy implements DeploymentStrategy {

// Git repository paths always use '/', regardless of the host OS
private static final String GIT_PATH_SEPARATOR = "/";

private final ArgoCdApplicationTargetResolver targetResolver;

@Override
public void deployFeature(
	String repoURL,
	String repoName,
	String chartOrPath,
	String version,
	String namespace,
	String releaseName,
	Path helmValuesPath,
	RepoType repoType,
	DeploymentContext context,
	RepositoryWorkspace repositoryWorkspace) {

	log.trace(
		"Deploying helm chart via ArgoCD: {}. Reading values from {}", releaseName, helmValuesPath);

	GitRepo clusterResourcesRepo = repositoryWorkspace.getClusterResourcesRepository();

	String toolName = repoName;

	String toolPath = "apps/" + toolName;
	String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir();
	Path.of(repoRoot, toolPath).toFile().mkdirs();
	Path.of(repoRoot, "apps/argocd/applications").toFile().mkdirs();

	String inlineValues;
	try {
	inlineValues = Files.readString(helmValuesPath);
	} catch (IOException e) {
	throw new UncheckedIOException(e);
	}

	ValuesFilePaths valuesFilePaths = ValuesFilePaths.of(toolPath, toolName, repoRoot);

	boolean bootstrapDeploymentRequired = requiresBootstrapDeployment(toolName);
	ArgoCdApplicationTarget target = targetResolver.resolve(context, repoName);

	if (bootstrapDeploymentRequired) {
	log.info(
		"Using bootstrap deployment for tool '{}': applicationName='{}', releaseName='{}', namespace='{}'. "
			+ "Helm values will be embedded into the ArgoCD Application and no external values source will be referenced.",
		toolName,
		target.getApplicationName(),
		releaseName,
		namespace);
	} else {
	writeValuesFiles(clusterResourcesRepo, toolName, valuesFilePaths, inlineValues);
	}

	Map<String, Object> helmSource = new LinkedHashMap<>();
	helmSource.put("repoURL", repoURL);
	helmSource.put(chooseKeyChartOrPath(repoType), chartOrPath);
	helmSource.put("targetRevision", version);
	helmSource.put(
		"helm",
		buildHelmValuesConfig(
			releaseName, bootstrapDeploymentRequired, toolName, inlineValues, valuesFilePaths));

	List<Map<String, Object>> sources = new ArrayList<>();
	sources.add(helmSource);

	if (!bootstrapDeploymentRequired) {
	sources.add(buildGitValuesSource(clusterResourcesRepo, toolPath));
	}

	String yamlResult = renderApplicationYaml(target, namespace, sources);

	String appManifestPath = "apps/argocd/applications/" + releaseName + ".yaml";

	try {
	clusterResourcesRepo.writeFile(appManifestPath, yamlResult);
	} catch (Exception e) {
	throw new RuntimeException(
		"Failed to write ArgoCD application manifest for " + releaseName, e);
	}

	log.debug(
		"Prepared ArgoCD application for helm release {} basing on chart {} from {}, version {}, into namespace {}. Application was written to shared repository workspace:\n{}",
		releaseName,
		chartOrPath,
		repoURL,
		version,
		namespace,
		yamlResult);
}

private static void writeValuesFiles(
	GitRepo clusterResourcesRepo,
	String toolName,
	ValuesFilePaths valuesFilePaths,
	String inlineValues) {
	try {
	clusterResourcesRepo.writeFile(valuesFilePaths.gopValuesPath(), inlineValues);

	if (!valuesFilePaths.userValuesAbsPath().toFile().exists()) {
		clusterResourcesRepo.writeFile(valuesFilePaths.userValuesPath(), "");
	}
	} catch (Exception e) {
	throw new RuntimeException("Failed to write values files for " + toolName, e);
	}
}

private static Map<String, Object> buildHelmValuesConfig(
	String releaseName,
	boolean bootstrapDeploymentRequired,
	String toolName,
	String inlineValues,
	ValuesFilePaths valuesFilePaths) {
	Map<String, Object> helmConfig = new LinkedHashMap<>();
	helmConfig.put("releaseName", releaseName);

	if (bootstrapDeploymentRequired) {
	log.trace(
		"Embedding Helm values for bootstrap tool '{}' directly into the ArgoCD Application to avoid a self-referencing values source.",
		toolName);
	helmConfig.put("values", inlineValues);
	} else {
	helmConfig.put(
		"valueFiles",
		List.of(
			"$values/" + valuesFilePaths.gopValuesPath(),
			"$values/" + valuesFilePaths.userValuesPath()));
	helmConfig.put("ignoreMissingValueFiles", true);
	}
	return helmConfig;
}

/**
* Locations of the gop and user Helm values files of a tool within the cluster-resources repo.
*/
private record ValuesFilePaths(
	String gopValuesPath, String userValuesPath, Path userValuesAbsPath) {

	static ValuesFilePaths of(String toolPath, String toolName, String repoRoot) {
	String gopValuesPath = toolPath + GIT_PATH_SEPARATOR + toolName + "-gop-helm.yaml";
	String userValuesPath = toolPath + GIT_PATH_SEPARATOR + toolName + "-user-values.yaml";
	return new ValuesFilePaths(gopValuesPath, userValuesPath, Path.of(repoRoot, userValuesPath));
	}
}

private static Map<String, Object> buildGitValuesSource(
	GitRepo clusterResourcesRepo, String toolPath) {
	String toolRepoUrl =
		clusterResourcesRepo.getGitProvider().repoPrefix() + "argocd/cluster-resources.git";

	Map<String, Object> gitSource = new LinkedHashMap<>();
	gitSource.put("repoURL", toolRepoUrl);
	gitSource.put("targetRevision", "main");
	gitSource.put("ref", "values");
	gitSource.put("path", toolPath);
	gitSource.put("directory", Map.of("recurse", true));
	return gitSource;
}

private static String renderApplicationYaml(
	ArgoCdApplicationTarget target, String namespace, List<Map<String, Object>> sources) {
	String namespaceCreationSyncOption = "CreateNamespace=" + target.isCreateDestinationNamespace();

	Map<String, Object> syncPolicy = new LinkedHashMap<>();
	Map<String, Object> automated = new LinkedHashMap<>();
	automated.put("prune", true);
	automated.put("selfHeal", true);
	syncPolicy.put("automated", automated);
	syncPolicy.put("syncOptions", List.of("ServerSideApply=true", namespaceCreationSyncOption));

	Map<String, Object> application = new LinkedHashMap<>();
	application.put("apiVersion", "argoproj.io/v1alpha1");
	application.put("kind", "Application");

	Map<String, Object> metadata = new LinkedHashMap<>();
	metadata.put("name", target.getApplicationName());
	metadata.put("namespace", target.getNamespace());
	application.put("metadata", metadata);

	Map<String, Object> spec = new LinkedHashMap<>();
	Map<String, Object> destination = new LinkedHashMap<>();
	destination.put("server", "https://kubernetes.default.svc");
	destination.put("namespace", namespace);
	spec.put("destination", destination);
	spec.put("project", target.getProject());
	spec.put("sources", sources);
	spec.put("syncPolicy", syncPolicy);
	application.put("spec", spec);

	YAMLMapper yamlMapper =
		YAMLMapper.builder().enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE).build();
	try {
	return yamlMapper.writeValueAsString(application);
	} catch (JsonProcessingException e) {
	throw new UncheckedIOException("Failed to generate YAML for ArgoCD application", e);
	}
}

public String chooseKeyChartOrPath(RepoType repoType) {
	return switch (repoType) {
	case HELM -> "chart";
	case GIT -> "path";
	default ->
		throw new IllegalStateException(
			"Repo type " + repoType + " not implemented for " + this.getClass().getSimpleName());
	};
}

private static boolean requiresBootstrapDeployment(String toolName) {
	return "scm-manager".equals(toolName);
}
}
