package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
class AirGappedUtils {

    private Config config
    private ScmmRepoProvider repoProvider
    private ScmmApiClient scmmApiClient
    private FileSystemUtils fileSystemUtils
    private HelmClient helmClient

    AirGappedUtils(Config config, ScmmRepoProvider repoProvider, ScmmApiClient scmmApiClient,
                   FileSystemUtils fileSystemUtils, HelmClient helmClient) {
        this.config = config
        this.repoProvider = repoProvider
        this.scmmApiClient = scmmApiClient
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient
    }

    /**
     * In air-gapped mode, the chart's dependencies can't be resolved.
     * As helm does not provide an option for changing them interactively, we push the charts into a separate repo. 
     * We alter these repos to resolve dependencies locally from SCMHandler.
     * 
     * @return the repo namespace and name
     */
    String mirrorHelmRepoToGit(Config.HelmConfig helmConfig) {
        String repoName = helmConfig.chart
        String namespace = ScmmRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES
        def repoNamespaceAndName = "${namespace}/${repoName}"
        def localHelmChartFolder = "${config.application.localHelmChartFolder}/${repoName}"

        validateChart(repoNamespaceAndName, localHelmChartFolder, repoName)

        ScmmRepo repo = repoProvider.getRepo(repoNamespaceAndName)
        repo.create("Mirror of Helm chart $repoName from ${helmConfig.repoURL}", scmmApiClient)
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

    private Map localizeChartYaml(ScmmRepo scmmRepo) {
        log.debug("Preparing repo ${scmmRepo.scmmRepoTarget} for air-gapped use: Changing Chart.yaml to resolve depencies locally")

        def chartYamlPath = Path.of(scmmRepo.absoluteLocalRepoTmpDir, 'Chart.yaml')

        Map chartYaml = new YamlSlurper().parse(chartYamlPath) as Map
        Map chartLock = parseChartLockIfExists(scmmRepo)

        List<Map> dependencies = chartYaml.dependencies as List<Map> ?: []
        for (Map chartYamlDep : dependencies) {
            resolveDependencyVersion(chartLock, chartYamlDep, scmmRepo)

            // Remove link to external repo, to force using local one
            chartYamlDep.repository = ''
        }
        fileSystemUtils.writeYaml(chartYaml, chartYamlPath.toFile())
        return chartYaml
    }

    private static Map parseChartLockIfExists(ScmmRepo scmmRepo) {
        def chartLock = Path.of(scmmRepo.absoluteLocalRepoTmpDir, 'Chart.lock')
        if (!chartLock.toFile().exists()) {
            return [:]
        }
        new YamlSlurper().parse(chartLock) as Map
    }

    /**
     * Resolve proper dependency version from Chart.lock, e.g. 5.18.* -> 5.18.1
     */
    private void resolveDependencyVersion(Map chartLock, Map chartYamlDep, ScmmRepo scmmRepo) {
        def chartLockDep = findByName(chartLock.dependencies as List, chartYamlDep.name as String)
        if (chartLockDep) {
            chartYamlDep.version = chartLockDep.version
        } else if ((chartYamlDep.version as String).contains('*')) {
            throw new RuntimeException("Unable to determine proper version for dependency " +
                    "${chartYamlDep.name} (version: ${chartYamlDep.version}) from repo ${scmmRepo.scmmRepoTarget}")
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