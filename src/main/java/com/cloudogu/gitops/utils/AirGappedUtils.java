package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.HelmConfig;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import groovy.yaml.YamlSlurper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class AirGappedUtils {

  private static final String VERSION_KEY = "version";

  private final Config config;
  private final GitRepoFactory repoProvider;
  private final FileSystemUtils fileSystemUtils;
  private final HelmClient helmClient;
  private final GitHandler gitHandler;

  /**
   * In air-gapped mode, the chart's dependencies can't be resolved. As helm does not provide an
   * option for changing them interactively, we push the charts into a separate repo. We alter these
   * repos to resolve dependencies locally from SCM.
   *
   * @return the repo namespace and name
   */
  public String mirrorHelmRepoToGit(HelmConfig helmConfig) {
    String repoName = helmConfig.getChart();
    String namespace = GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES;
    String repoNamespaceAndName = namespace + "/" + repoName;
    String localHelmChartFolder =
        config.getApplication().getLocalHelmChartFolder() + "/" + repoName;

    validateChart(repoNamespaceAndName, localHelmChartFolder, repoName);

    GitRepo repo = repoProvider.create(repoNamespaceAndName, gitHandler.getTenant());

    try {
      repo.createRepositoryAndSetPermission(
          "Mirror of Helm chart " + repoName + " from " + helmConfig.getRepoURL(), false);

      repo.cloneRepo();

      repo.copyDirectoryContents(localHelmChartFolder);

      Map<String, Object> chartYaml = localizeChartYaml(repo);

      // Chart.lock contains pinned dependencies and digest.
      // We either have to update or remove them. Take the easier approach.
      Files.deleteIfExists(Path.of(repo.getAbsoluteLocalRepoTmpDir(), "Chart.lock"));

      repo.commitAndPush(
          "Chart "
              + chartYaml.get("name")
              + ", version: "
              + chartYaml.get(VERSION_KEY)
              + "\n\n"
              + "Source: "
              + helmConfig.getRepoURL()
              + "\n"
              + "Dependencies localized to run in air-gapped environments",
          String.valueOf(chartYaml.get(VERSION_KEY)));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to mirror helm repo to Git for " + repoName, e);
    }
    return repoNamespaceAndName;
  }

  private void validateChart(
      String repoNamespaceAndName, String localHelmChartFolder, String repoName) {
    log.debug(
        "Validating helm chart before pushing it to SCM, by running helm template.\n"
            + "Potential repo: {}, chart folder: {}",
        repoNamespaceAndName,
        localHelmChartFolder);
    try {
      helmClient.template(repoName, localHelmChartFolder);
    } catch (RuntimeException e) {
      throw new RuntimeException(
          "Helm chart in folder " + localHelmChartFolder + " seems invalid.", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> localizeChartYaml(GitRepo gitRepo) {
    log.debug(
        "Preparing repo {} for air-gapped use: Changing Chart.yaml to resolve depencies locally",
        gitRepo.getRepoTarget());

    Path chartYamlPath = Path.of(gitRepo.getAbsoluteLocalRepoTmpDir(), "Chart.yaml");

    Map<String, Object> chartYaml;
    try {
      chartYaml = (Map<String, Object>) new YamlSlurper().parse(chartYamlPath.toFile());
    } catch (IOException e) {
      throw new RuntimeException("Failed to parse Chart.yaml: " + chartYamlPath, e);
    }
    Map<String, Object> chartLock = parseChartLockIfExists(gitRepo);

    List<Map<String, Object>> dependencies =
        (List<Map<String, Object>>) chartYaml.get("dependencies");
    if (dependencies == null) {
      dependencies = Collections.emptyList();
    }
    for (Map<String, Object> chartYamlDep : dependencies) {
      resolveDependencyVersion(chartLock, chartYamlDep, gitRepo);

      // Remove link to external repo, to force using local one
      chartYamlDep.put("repository", "");
    }
    fileSystemUtils.writeYaml(chartYaml, chartYamlPath.toFile());
    return chartYaml;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseChartLockIfExists(GitRepo scmmRepo) {
    Path chartLock = Path.of(scmmRepo.getAbsoluteLocalRepoTmpDir(), "Chart.lock");
    if (!chartLock.toFile().exists()) {
      return Collections.emptyMap();
    }
    try {
      return (Map<String, Object>) new YamlSlurper().parse(chartLock.toFile());
    } catch (IOException e) {
      throw new RuntimeException("Failed to parse Chart.lock: " + chartLock, e);
    }
  }

  /** Resolve proper dependency version from Chart.lock, e.g. 5.18.* -> 5.18.1 */
  @SuppressWarnings("unchecked")
  private void resolveDependencyVersion(
      Map<String, Object> chartLock, Map<String, Object> chartYamlDep, GitRepo gitRepo) {
    List<Map<String, Object>> lockDependencies =
        (List<Map<String, Object>>) chartLock.get("dependencies");
    Map<String, Object> chartLockDep =
        findByName(lockDependencies, String.valueOf(chartYamlDep.get("name")));
    if (chartLockDep != null && !chartLockDep.isEmpty()) {
      chartYamlDep.put(VERSION_KEY, chartLockDep.get(VERSION_KEY));
    } else if (String.valueOf(chartYamlDep.get(VERSION_KEY)).contains("*")) {
      throw new RuntimeException(
          "Unable to determine proper version for dependency "
              + chartYamlDep.get("name")
              + " (version: "
              + chartYamlDep.get(VERSION_KEY)
              + ") from repo "
              + gitRepo.getRepoTarget());
    } else {
      // version is already pinned (no wildcard); keep it as-is
    }
  }

  public Map<String, Object> findByName(List<Map<String, Object>> list, String name) {
    if (list == null || list.isEmpty()) {
      return Collections.emptyMap();
    }
    return list.stream()
        .filter(map -> name.equals(map.get("name")))
        .findFirst()
        .orElse(Collections.emptyMap());
  }
}
