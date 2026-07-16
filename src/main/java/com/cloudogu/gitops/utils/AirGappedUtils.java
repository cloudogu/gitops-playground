package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.HelmConfig;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import groovy.yaml.YamlSlurper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Singleton
@SuppressWarnings({"rawtypes", "unchecked"})
public class AirGappedUtils {

    private static final Logger log = LoggerFactory.getLogger(AirGappedUtils.class);

    private final Config config;
    private final GitRepoFactory repoProvider;
    private final FileSystemUtils fileSystemUtils;
    private final HelmClient helmClient;
    private final GitHandler gitHandler;

    public AirGappedUtils(Config config, GitRepoFactory repoProvider,
                          FileSystemUtils fileSystemUtils, HelmClient helmClient, GitHandler gitHandler) {
        this.config = config;
        this.repoProvider = repoProvider;
        this.fileSystemUtils = fileSystemUtils;
        this.helmClient = helmClient;
        this.gitHandler = gitHandler;
    }

    /**
     * In air-gapped mode, the chart's dependencies can't be resolved.
     * As helm does not provide an option for changing them interactively, we push the charts into a separate repo.
     * We alter these repos to resolve dependencies locally from SCM.
     *
     * @return the repo namespace and name
     */
    public String mirrorHelmRepoToGit(HelmConfig helmConfig) {
        String repoName = helmConfig.getChart();
        String namespace = GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES;
        String repoNamespaceAndName = namespace + "/" + repoName;
        String localHelmChartFolder = config.getApplication().getLocalHelmChartFolder() + "/" + repoName;

        validateChart(repoNamespaceAndName, localHelmChartFolder, repoName);

        GitRepo repo = repoProvider.create(repoNamespaceAndName, gitHandler.getTenant());

        try {
            repo.createRepositoryAndSetPermission("Mirror of Helm chart " + repoName + " from " + helmConfig.getRepoURL(), false);

            repo.cloneRepo();

            repo.copyDirectoryContents(localHelmChartFolder);

            Map chartYaml = localizeChartYaml(repo);

            // Chart.lock contains pinned dependencies and digest.
            // We either have to update or remove them. Take the easier approach.
            new File(repo.getAbsoluteLocalRepoTmpDir(), "Chart.lock").delete();

            repo.commitAndPush("Chart " + chartYaml.get("name") + ", version: " + chartYaml.get("version") + "\n\n" +
                    "Source: " + helmConfig.getRepoURL() + "\n" +
                    "Dependencies localized to run in air-gapped environments", String.valueOf(chartYaml.get("version")));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to mirror helm repo to Git for " + repoName, e);
        }
        return repoNamespaceAndName;
    }

    private void validateChart(String repoNamespaceAndName, String localHelmChartFolder, String repoName) {
        log.debug("Validating helm chart before pushing it to SCM, by running helm template.\n" +
                "Potential repo: {}, chart folder: {}", repoNamespaceAndName, localHelmChartFolder);
        try {
            helmClient.template(repoName, localHelmChartFolder);
        } catch (RuntimeException e) {
            throw new RuntimeException("Helm chart in folder " + localHelmChartFolder + " seems invalid.", e);
        }
    }

    private Map localizeChartYaml(GitRepo gitRepo) {
        log.debug("Preparing repo {} for air-gapped use: Changing Chart.yaml to resolve depencies locally", gitRepo.getRepoTarget());

        Path chartYamlPath = Path.of(gitRepo.getAbsoluteLocalRepoTmpDir(), "Chart.yaml");

        Map chartYaml;
        try {
            chartYaml = (Map) new YamlSlurper().parse(chartYamlPath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Chart.yaml: " + chartYamlPath, e);
        }
        Map chartLock = parseChartLockIfExists(gitRepo);

        List<Map> dependencies = (List<Map>) chartYaml.get("dependencies");
        if (dependencies == null) {
            dependencies = Collections.emptyList();
        }
        for (Map chartYamlDep : dependencies) {
            resolveDependencyVersion(chartLock, chartYamlDep, gitRepo);

            // Remove link to external repo, to force using local one
            chartYamlDep.put("repository", "");
        }
        fileSystemUtils.writeYaml(chartYaml, chartYamlPath.toFile());
        return chartYaml;
    }

    private static Map parseChartLockIfExists(GitRepo scmmRepo) {
        Path chartLock = Path.of(scmmRepo.getAbsoluteLocalRepoTmpDir(), "Chart.lock");
        if (!chartLock.toFile().exists()) {
            return Collections.emptyMap();
        }
        try {
            return (Map) new YamlSlurper().parse(chartLock.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Chart.lock: " + chartLock, e);
        }
    }

    /**
     * Resolve proper dependency version from Chart.lock, e.g. 5.18.* -> 5.18.1
     */
    private void resolveDependencyVersion(Map chartLock, Map chartYamlDep, GitRepo gitRepo) {
        List<Map> lockDependencies = (List<Map>) chartLock.get("dependencies");
        Map chartLockDep = findByName(lockDependencies, String.valueOf(chartYamlDep.get("name")));
        if (chartLockDep != null && !chartLockDep.isEmpty()) {
            chartYamlDep.put("version", chartLockDep.get("version"));
        } else if (String.valueOf(chartYamlDep.get("version")).contains("*")) {
            throw new RuntimeException("Unable to determine proper version for dependency " +
                    chartYamlDep.get("name") + " (version: " + chartYamlDep.get("version") + ") from repo " + gitRepo.getRepoTarget());
        }
    }

    public Map findByName(List<Map> list, String name) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        return list.stream()
                .filter(map -> name.equals(map.get("name")))
                .findFirst().orElse(Collections.emptyMap());
    }
}
