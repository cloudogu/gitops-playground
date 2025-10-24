package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.TestGitRepoFactory
import com.cloudogu.gitops.git.providers.scmmanager.Permission
import com.cloudogu.gitops.utils.git.ScmManagerMock
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.utils.git.TestScmManagerApiClient
import groovy.yaml.YamlSlurper
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

class AirGappedUtilsTest {

    Config config = Config.fromMap([
            application: [
                    localHelmChartFolder: '',
                    gitName             : 'Cloudogu',
                    gitEmail            : 'hello@cloudogu.com'],
            scm        : [
                    scmManager: [
                            url: '']
            ]
    ])

    Config.HelmConfig helmConfig = new Config.HelmConfig([
            chart  : 'kube-prometheus-stack',
            repoURL: 'https://kube-prometheus-stack-repo-url',
            version: '58.2.1'
    ])

    Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
    TestGitRepoFactory gitRepoFactory = new TestGitRepoFactory(config, new FileSystemUtils())
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    TestScmManagerApiClient scmmApiClient = new TestScmManagerApiClient(config)
    HelmClient helmClient = mock(HelmClient)
    GitHandler gitHandler = new GitHandlerForTests(config, new ScmManagerMock())

    @BeforeEach
    void setUp() {
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

    }

    @Test
    void 'Prepares repos for air-gapped use'() {
        setupForAirgappedUse()

        def actualRepoNamespaceAndName = createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)

        assertThat(actualRepoNamespaceAndName).isEqualTo(
                "${GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/kube-prometheus-stack".toString())
        assertAirGapped()
    }

    @Test
    void 'Fails when unable to resolve version of dependencies'() {
        setupForAirgappedUse([:])
        def exception = shouldFail(RuntimeException) {
            createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
        }

        assertThat(exception.message).isEqualTo(
                'Unable to determine proper version for dependency grafana (version: 7.3.*) ' +
                        'from repo 3rd-party-dependencies/kube-prometheus-stack'
        )
    }

    @Test
    void 'Also works for charts without dependencies'() {
        setupForAirgappedUse(null, [])
        createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)

        GitRepo prometheusRepo = gitRepoFactory.repos['3rd-party-dependencies/kube-prometheus-stack']
        def actualPrometheusChartYaml = new YamlSlurper().parse(Path.of(prometheusRepo.absoluteLocalRepoTmpDir, 'Chart.yaml'))

        def dependencies = actualPrometheusChartYaml['dependencies']
        assertThat(dependencies).isNull()
    }

    @Test
    void 'Fails for invalid helm charts'() {
        setupForAirgappedUse()

        def expectedException = new RuntimeException()
        doThrow(expectedException).when(helmClient).template(anyString(), anyString())

        def exception = shouldFail(RuntimeException) {
            createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
        }

        assertThat(exception.getMessage()).isEqualTo(
                "Helm chart in folder ${rootChartsFolder}/kube-prometheus-stack seems invalid.".toString())
        assertThat(exception.getCause()).isSameAs(expectedException)
    }

    protected void setupForAirgappedUse(Map chartLock = null, List dependencies = null) {
        Path sourceChart = rootChartsFolder.resolve('kube-prometheus-stack')
        Files.createDirectories(sourceChart)
        Map prometheusChartYaml = [
                version     : '1.2.3',
                name        : 'kube-prometheus-stack-chart',
                dependencies: [
                        [
                                condition : 'crds.enabled',
                                name      : 'crds',
                                repository: '',
                                version   : '0.0.0'
                        ],
                        [
                                condition : 'grafana.enabled',
                                name      : 'grafana',
                                repository: 'https://grafana-repo-url',
                                version   : '7.3.*',
                        ]
                ]
        ]

        if (dependencies != null) {
            if (dependencies.isEmpty()) {
                prometheusChartYaml.remove('dependencies')
            } else {
                prometheusChartYaml.dependencies = dependencies
            }
        }

        fileSystemUtils.writeYaml(prometheusChartYaml, sourceChart.resolve('Chart.yaml').toFile())

        if (chartLock == null) {
            chartLock = [
                    dependencies: [
                            [
                                    name      : 'crds',
                                    repository: "",
                                    version   : '0.0.0'
                            ],
                            [
                                    name      : 'grafana',
                                    repository: 'https://grafana.github.io/helm-charts',
                                    version   : '7.3.9'
                            ]
                    ]
            ]
        }
        fileSystemUtils.writeYaml(chartLock, sourceChart.resolve('Chart.lock').toFile())

        config.application.localHelmChartFolder = rootChartsFolder.toString()
    }

    protected void assertAirGapped() {
        GitRepo prometheusRepo = gitRepoFactory.repos['3rd-party-dependencies/kube-prometheus-stack']
        assertThat(prometheusRepo).isNotNull()
        assertThat(Path.of(prometheusRepo.absoluteLocalRepoTmpDir, 'Chart.lock')).doesNotExist()

        def ys = new YamlSlurper()
        def actualPrometheusChartYaml = ys.parse(Path.of(prometheusRepo.absoluteLocalRepoTmpDir, 'Chart.yaml'))
        assertThat(actualPrometheusChartYaml['name']).isEqualTo('kube-prometheus-stack-chart')

        def dependencies = actualPrometheusChartYaml['dependencies'] as List
        assertThat(dependencies).hasSize(2)
        assertThat(dependencies[0]['name']).isEqualTo('crds')
        assertThat(dependencies[0]['version']).isEqualTo('0.0.0')
        assertThat(dependencies[0]['repository']).isEqualTo('')
        assertThat(dependencies[1]['name']).isEqualTo('grafana')
        assertThat(dependencies[1]['version']).isEqualTo('7.3.9')
        assertThat(dependencies[1]['repository']).isEqualTo('')

        assertHelmRepoCommits(prometheusRepo, '1.2.3', 'Chart kube-prometheus-stack-chart, version: 1.2.3\n\n' +
                'Source: https://kube-prometheus-stack-repo-url\nDependencies localized to run in air-gapped environments')

        verify(prometheusRepo).createRepositoryAndSetPermission(
                eq("${GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/kube-prometheus-stack".toString()),
                eq("Mirror of Helm chart kube-prometheus-stack from https://kube-prometheus-stack-repo-url"),
                eq(false)
        )
    }


    void assertHelmRepoCommits(GitRepo repo, String expectedTag, String expectedCommitMessage) {
        def commits = Git.open(new File(repo.absoluteLocalRepoTmpDir)).log().setMaxCount(1).all().call().collect()
        assertThat(commits.size()).isEqualTo(1)
        assertThat(commits[0].fullMessage).isEqualTo(expectedCommitMessage)

        List<Ref> tags = Git.open(new File(repo.absoluteLocalRepoTmpDir)).tagList().call()
        assertThat(tags.size()).isEqualTo(1)
        assertThat(tags[0].name).isEqualTo("refs/tags/${expectedTag}".toString())
    }

    AirGappedUtils createAirGappedUtils() {
        new AirGappedUtils(config, gitRepoFactory, fileSystemUtils, helmClient, gitHandler)
    }
}