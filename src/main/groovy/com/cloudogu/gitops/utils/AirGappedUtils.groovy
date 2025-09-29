package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Config.HelmConfig
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.GitRepoFactory
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import jakarta.inject.Singleton
import java.nio.file.Path

@Slf4j
@Singleton
class AirGappedUtils {

    private Config config
    private GitRepoFactory repoProvider
    private FileSystemUtils fileSystemUtils
    private HelmClient helmClient
    private GitHandler gitHandler

    AirGappedUtils(Config config, GitRepoFactory repoProvider,
                   FileSystemUtils fileSystemUtils, HelmClient helmClient, GitHandler gitHandler) {
        this.config = config
        this.repoProvider = repoProvider
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient
        this.gitHandler = gitHandler
    }

    /**
     * In air-gapped mode, the chart's dependencies can't be resolved.
     * As helm does not provide an option for changing them interactively, we push the charts into a separate repo. 
     * We alter these repos to resolve dependencies locally from SCMHandler.
     *
     * @return the repo namespace and name
     */
    String mirrorHelmRepoToGit(HelmConfig helmConfig) {
        String repoName = helmConfig.chart
        String namespace = GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES
        String repoNamespaceAndName = "${namespace}/${repoName}"
        String localHelmChartFolder = "${config.application.localHelmChartFolder}/${repoName}"

        validateChart(repoNamespaceAndName, localHelmChartFolder, repoName)

        GitRepo repo = repoProvider.getRepo(repoNamespaceAndName, gitHandler.tenant)

        //TODO 3th Party where? 3th party is within GitRepo
        repo.createRepositoryAndSetPermission(repoNamespaceAndName, "Mirror of Helm chart $repoName from ${helmConfig.repoURL}", false)

        repo.cloneRepo()

        repo.copyDirectoryContents(localHelmChartFolder)

        def chartYaml = localizeChartYaml(repo)

        // Chart.lock contains pinned dependencies and digest.
        // We either have to update or remove them. Take the easier approach.
        new File(repo.absoluteLocalRepoTmpDir, 'Chart.lock').delete()

        repo.commitAndPush("Chart ${chartYaml.name}, version: ${chartYaml.version}\n\n" +
                "Source: ${helmConfig.repoURL}\n" +
                "Dependencies localized to run in air-gapped environments", chartYaml.version as String)
        return repoNamespaceAndName
    }

    private void validateChart(repoNamespaceAndName, String localHelmChartFolder, String repoName) {
        log.debug("Validating helm chart before pushing it to SCMHandler, by running helm template.\n" +
                "Potential repo: ${repoNamespaceAndName}, chart folder: ${localHelmChartFolder}")
        try {
            helmClient.template(repoName, localHelmChartFolder)
        } catch (RuntimeException e) {
            throw new RuntimeException("Helm chart in folder ${localHelmChartFolder} seems invalid.", e)
        }
    }

    private Map localizeChartYaml(GitRepo gitRepo) {
        log.debug("Preparing repo ${gitRepo.repoTarget} for air-gapped use: Changing Chart.yaml to resolve depencies locally")

        def chartYamlPath = Path.of(gitRepo.absoluteLocalRepoTmpDir, 'Chart.yaml')

        Map chartYaml = new YamlSlurper().parse(chartYamlPath) as Map
        Map chartLock = parseChartLockIfExists(gitRepo)

        List<Map> dependencies = chartYaml.dependencies as List<Map> ?: []
        for (Map chartYamlDep : dependencies) {
            resolveDependencyVersion(chartLock, chartYamlDep, gitRepo)

            // Remove link to external repo, to force using local one
            chartYamlDep.repository = ''
        }
        fileSystemUtils.writeYaml(chartYaml, chartYamlPath.toFile())
        return chartYaml
    }

    private static Map parseChartLockIfExists(GitRepo scmmRepo) {
        def chartLock = Path.of(scmmRepo.absoluteLocalRepoTmpDir, 'Chart.lock')
        if (!chartLock.toFile().exists()) {
            return [:]
        }
        new YamlSlurper().parse(chartLock) as Map
    }

    /**
     * Resolve proper dependency version from Chart.lock, e.g. 5.18.* -> 5.18.1
     */
    private void resolveDependencyVersion(Map chartLock, Map chartYamlDep, GitRepo gitRepo) {
        def chartLockDep = findByName(chartLock.dependencies as List, chartYamlDep.name as String)
        if (chartLockDep) {
            chartYamlDep.version = chartLockDep.version
        } else if ((chartYamlDep.version as String).contains('*')) {
            throw new RuntimeException("Unable to determine proper version for dependency " +
                    "${chartYamlDep.name} (version: ${chartYamlDep.version}) from repo ${gitRepo.repoTarget}")
        }
    }

    Map findByName(List<Map> list, String name) {
        if (!list) return [:]
        // Note that list.find{} does not work in GraalVM native image: 
        // UnsupportedFeatureError: Runtime reflection is not supported
        list.stream()
                .filter(map -> map.name == name)
                .findFirst().orElse([:])
    }
}