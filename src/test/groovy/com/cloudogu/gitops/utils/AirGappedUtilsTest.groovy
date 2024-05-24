package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.RepositoryApi
import groovy.yaml.YamlSlurper
import okhttp3.internal.http.RealResponseBody
import okio.BufferedSource
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import retrofit2.Call
import retrofit2.Response

import java.nio.file.Files
import java.nio.file.Path

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

class AirGappedUtilsTest {

    Map config = [
            application: [
                    localHelmChartFolder : '',
                    gitName : 'Cloudogu',
                    gitEmail : 'hello@cloudogu.com',
            ],
            scmm: [
                    username: 'scmm-usr',
                    password: 'scmm-pw',
                    gitOpsUsername: 'foo-gitops'
            ]
    ]
    
    Map helmConfig = [
            chart  : 'kube-prometheus-stack',
            repoURL: 'https://kube-prometheus-stack-repo-url',
            version: '58.2.1'
    ]
    
    Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
    TestScmmRepoProvider scmmRepoProvider = new TestScmmRepoProvider(new Configuration(config), new FileSystemUtils())
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    RepositoryApi repositoryApi = mock(RepositoryApi)
    HelmClient helmClient = mock(HelmClient)

    @Test
    void 'Prepares repos for air-gapped use'() {
        setupForAirgappedUse()

        def response = mockSuccessfulResponse(201)
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        def actualRepoNamespaceAndName = createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
        
        assertThat(actualRepoNamespaceAndName).isEqualTo(
                "${ScmmRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/kube-prometheus-stack".toString())
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

        ScmmRepo prometheusRepo = scmmRepoProvider.repos['3rd-party-dependencies/kube-prometheus-stack']
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

    @Test
    void 'Ignores existing Repos'() {
        setupForAirgappedUse()

        def errorResponse = this.mockErrorResponse(409)
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(errorResponse)

        createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)

        assertAirGapped()
    }

    @Test
    void 'Ignores existing Permissions'() {
        setupForAirgappedUse()

        def errorResponse = mockErrorResponse(409)
        def successfulResponse = mockSuccessfulResponse(201)

        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(successfulResponse)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(errorResponse)

        createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)

        assertAirGapped()
    }

    @Test
    void 'Handles failures to SCMM-API for Repos'() {
        setupForAirgappedUse()

        def errorResponse = this.mockErrorResponse(500)

        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(errorResponse)

        def exception = shouldFail(RuntimeException) {
            createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
        }
        assertThat(exception.message).startsWith('Could not create Repository')
        assertThat(exception.message).contains('3rd-party-dependencies')
        assertThat(exception.message).contains('kube-prometheus-stack')
        assertThat(exception.message).contains('500')
    }

    @Test
    void 'Handles failures to SCMM-API for Permissions'() {
        setupForAirgappedUse()

        def errorResponse = mockErrorResponse(500)
        def successfulResponse = mockSuccessfulResponse(201)

        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(successfulResponse)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(errorResponse)

        def exception = shouldFail(RuntimeException) {
            createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
        }
        assertThat(exception.message).startsWith('Could not create Permission for repo 3rd-party-dependencies/kube-prometheus-stack')
        assertThat(exception.message).contains('foo-gitops')
        assertThat(exception.message).contains(Permission.Role.WRITE.name())
        assertThat(exception.message).contains('500')
    }

    protected void setupForAirgappedUse(Map chartLock = null, List dependencies = null) {
        def response = mockSuccessfulResponse(201)
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        Path sourceChart = rootChartsFolder.resolve('kube-prometheus-stack')
        Files.createDirectories(sourceChart)
        Map prometheusChartYaml = [
                version     : '1.2.3',
                name        : 'kube-prometheus-stack-chart',
                dependencies: [
                        [
                                condition: 'crds.enabled',
                                name: 'crds',
                                repository: '',
                                version: '0.0.0'
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

        if(chartLock == null) {
            chartLock = [
                    dependencies: [
                            [
                                    name: 'crds',
                                    repository: "",
                                    version: '0.0.0'
                            ],
                            [
                                    name: 'grafana',
                                    repository: 'https://grafana.github.io/helm-charts',
                                    version: '7.3.9'
                            ]
                    ]
            ]
        }
        fileSystemUtils.writeYaml(chartLock, sourceChart.resolve('Chart.lock').toFile())

        config.application['localHelmChartFolder'] = rootChartsFolder.toString()
    }

    protected void assertAirGapped() {
        ScmmRepo prometheusRepo = scmmRepoProvider.repos['3rd-party-dependencies/kube-prometheus-stack']
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

        def repoCreateArgument = ArgumentCaptor.forClass(Repository)
        verify(repositoryApi, times(1)).create(repoCreateArgument.capture(), eq(true))
        assertThat(repoCreateArgument.allValues[0].namespace).isEqualTo(ScmmRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES)
        assertThat(repoCreateArgument.allValues[0].name).isEqualTo('kube-prometheus-stack')
        assertThat(repoCreateArgument.allValues[0].description).isEqualTo('Mirror of Helm chart kube-prometheus-stack from https://kube-prometheus-stack-repo-url')

        def permissionCreateArgument = ArgumentCaptor.forClass(Permission)
        verify(repositoryApi, times(1)).createPermission(anyString(), anyString(), permissionCreateArgument.capture())
        assertThat(permissionCreateArgument.allValues[0].name).isEqualTo('foo-gitops')
        assertThat(permissionCreateArgument.allValues[0].role).isEqualTo(Permission.Role.WRITE)
    }

    Call<Void> mockSuccessfulResponse(int expectedReturnCode) {
        def expectedCall = mock(Call<Void>)
        when(expectedCall.execute()).thenReturn(Response.success(expectedReturnCode, null))
        expectedCall
    }

    Call<Void> mockErrorResponse(int expectedReturnCode) {
        def expectedCall = mock(Call<Void>)
        // Response is a final class that cannot be mocked 😠
        Response<Void> errorResponse = Response.error(expectedReturnCode, new RealResponseBody('dontcare', 0, mock(BufferedSource)))
        when(expectedCall.execute()).thenReturn(errorResponse)
        expectedCall
    }

    void assertHelmRepoCommits(ScmmRepo repo, String expectedTag, String expectedCommitMessage) {
        def commits = Git.open(new File(repo.absoluteLocalRepoTmpDir)).log().setMaxCount(1).all().call().collect()
        assertThat(commits.size()).isEqualTo(1)
        assertThat(commits[0].fullMessage).isEqualTo(expectedCommitMessage)

        List<Ref> tags = Git.open(new File(repo.absoluteLocalRepoTmpDir)).tagList().call()
        assertThat(tags.size()).isEqualTo(1)
        assertThat(tags[0].name).isEqualTo("refs/tags/${expectedTag}".toString())
    }

    AirGappedUtils createAirGappedUtils() {
        new AirGappedUtils(new Configuration(config), scmmRepoProvider, repositoryApi, fileSystemUtils, helmClient)
    }
}
