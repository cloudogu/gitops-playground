package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.RepositoryApi
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import jakarta.inject.Singleton
import retrofit2.Response

import java.nio.file.Path

@Slf4j
@Singleton
class AirGappedUtils {

    private Map config
    private ScmmRepoProvider repoProvider
    private RepositoryApi repositoryApi
    private FileSystemUtils fileSystemUtils
    private HelmClient helmClient

    AirGappedUtils(Configuration config, ScmmRepoProvider repoProvider, RepositoryApi repositoryApi, 
                   FileSystemUtils fileSystemUtils, HelmClient helmClient) {
        this.config = config.getConfig()
        this.repoProvider = repoProvider
        this.repositoryApi = repositoryApi
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient
    }

    /**
     * In air-gapped mode, the chart's dependencies can't be resolved.
     * As helm does not provide an option for changing them interactively, we push the charts into a separate repo. 
     * We alter these repos to resolve dependencies locally from SCM.
     * 
     * @return the repo namespace and name
     */
    String mirrorHelmRepoToGit(Map helmConfig) {
        String repoName = helmConfig['chart']
        String namespace = ScmmRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES
        def repoNamespaceAndName = "${namespace}/${repoName}"
        def localHelmChartFolder = "${config.application['localHelmChartFolder']}/${repoName}"

        validateChart(repoNamespaceAndName, localHelmChartFolder, repoName)

        createRepo(namespace, repoName,"Mirror of Helm chart $repoName from ${helmConfig['repoURL']}")

        ScmmRepo repo = repoProvider.getRepo(repoNamespaceAndName)
        repo.cloneRepo()

        repo.copyDirectoryContents(localHelmChartFolder)

        def chartYaml = localizeChartYaml(repo)

        // Chart.lock contains pinned dependencies and digest.
        // We either have to update or remove them. Take the easier approach.
        new File(repo.absoluteLocalRepoTmpDir, 'Chart.lock').delete()

        repo.commitAndPush("Chart ${chartYaml.name}, version: ${chartYaml.version}\n\n" +
                "Source: ${helmConfig['repoURL']}\n" +
                "Dependencies localized to run in air-gapped environments", chartYaml.version as String)
        return repoNamespaceAndName
    }

    private void validateChart(repoNamespaceAndName, String localHelmChartFolder, String repoName) {
        log.debug("Validating helm chart before pushing it to SCM, by running helm template.\n" +
                "Potential repo: ${repoNamespaceAndName}, chart folder: ${localHelmChartFolder}")
        try {
            helmClient.template(repoName, localHelmChartFolder)
        } catch (RuntimeException e) {
            throw new RuntimeException("Helm chart in folder ${localHelmChartFolder} seems invalid.", e)
        }
    }

    // This could be moved to ScmmRepo, if needed
    private void createRepo(String namespace, String repoName, String description) {
        def repo = new Repository(namespace, repoName, description)
        def createResponse = repositoryApi.create(repo, true).execute()
        handleResponse(createResponse, repo)

        def permission = new Permission(config.scmm['gitOpsUsername'] as String, Permission.Role.WRITE)
        def permissionResponse = repositoryApi.createPermission(namespace, repoName, permission).execute()
        handleResponse(permissionResponse, permission, "for repo $namespace/$repoName")
    }

    private static void handleResponse(Response<Void> response, Object body, String additionalMessage = '') {
        if (response.code() == 409) {
            // Here, we could consider sending another request for changing the existing object to become proper idempotent
            log.debug("${body.class.simpleName} already exists ${additionalMessage}, ignoring: ${body}")
        } else if (response.code() != 201) {
            throw new RuntimeException("Could not create ${body.class.simpleName} ${additionalMessage}.\n${body}\n" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
    }

    private Map localizeChartYaml(ScmmRepo scmmRepo) {
        log.debug("Preparing repo ${scmmRepo.scmmRepoTarget} for air-gapped use: Changing Chart.yaml to resolve depencies locally")

        def chartYamlPath = Path.of(scmmRepo.absoluteLocalRepoTmpDir, 'Chart.yaml')
        def chartLockPath = Path.of(scmmRepo.absoluteLocalRepoTmpDir, 'Chart.lock')

        def ys = new YamlSlurper()
        Map chartYaml = ys.parse(chartYamlPath) as Map
        Map chartLock = ys.parse(chartLockPath) as Map

        def dependencies = chartYaml['dependencies'] ?: []
        (dependencies as List).each { chartYamlDep ->
            // Resolve proper dependency version from Chart.lock, e.g. 5.18.* -> 5.18.1
            def chartLockDep = findByName(chartLock.dependencies as List, chartYamlDep['name'] as String)
            if (chartLockDep) {
                chartYamlDep['version'] = chartLockDep['version']
            } else if ((chartYamlDep['version'] as String).contains('*')) {
                throw new RuntimeException("Unable to determine proper version for dependency " +
                        "${chartYamlDep['name']} (version: ${chartYamlDep['version']}) from repo ${scmmRepo.scmmRepoTarget}")
            }

            // Remove link to external repo, to force using local one
            chartYamlDep['repository'] = ''
        }

        fileSystemUtils.writeYaml(chartYaml, chartYamlPath.toFile())
        return chartYaml
    }

    Map findByName(List<Map> list, String name) {
        if (!list) return [:]
        // Note that list.find{} does not work in GraalVM native image: 
        // UnsupportedFeatureError: Runtime reflection is not supported
        list.stream()
                .filter(map -> map['name'] == name)
                .findFirst().orElse([:])
    }
}
