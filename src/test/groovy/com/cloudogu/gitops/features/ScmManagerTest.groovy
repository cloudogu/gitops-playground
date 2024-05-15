package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.TestScmmRepoProvider
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

class ScmManagerTest {

    Map config = [
            application : [
                    username  : 'abc',
                    password  : '123',
                    remote    : false,
                    namePrefix: "foo-",
                    trace     : true,
                    //baseUrl : 'http://localhost',
                    insecure  : false,
                    gitName   : 'Cloudogu',
                    gitEmail  : 'hello@cloudogu.com',
            ],
            scmm        : [
                    url          : 'http://scmm',
                    internal     : true,
                    protocol     : 'https',
                    host         : 'abc',
                    ingress      : 'scmm.localhost',
                    username     : 'scmm-usr',
                    password     : 'scmm-pw',
                    urlForJenkins: 'http://scmm4jenkins',
                    helm         : [
                            chart  : 'scm-manager-chart',
                            version: '2.47.0',
                            repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                    ]
            ],
            jenkins     : [
                    internal  : true,
                    url       : 'http://jenkins',
                    urlForScmm: 'http://jenkins4scm',
            ],
            features    : [
                    argocd: [
                            active: true,
                    ],
                    monitoring: [
                            active: true,
                            helm  : [
                                    chart  : 'kube-prometheus-stack',
                                    repoURL: 'https://kube-prometheus-stack-repo-url',
                                    version: '58.2.1',
                                    localFolder: ''
                                    ]
                    ]
            ],
            repositories: [
                    springBootHelmChart: [
                            url: 'springBootHelmChartUrl',
                            ref: '1.2.3'
                    ],
                    gitopsBuildLib     : [
                            url: 'gitopsBuildLibUrl'
                    ],
                    cesBuildLib        : [
                            url: 'cesBuildLibUrl'
                    ]
            ],
    ]

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()

    File temporaryYamlFile
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(helmCommands)
    TestScmmRepoProvider scmmRepoProvider = new TestScmmRepoProvider(new Configuration(config), new FileSystemUtils())
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    RepositoryApi repositoryApi = mock(RepositoryApi)

    @Test
    void 'Installs SCMM and calls script with proper params'() {
        createScmManager().install()

        assertThat(parseActualYaml()['extraEnv'] as String).contains('SCM_WEBAPP_INITIALUSER\n  value: "scmm-usr"')
        assertThat(parseActualYaml()['extraEnv'] as String).contains('SCM_WEBAPP_INITIALPASSWORD\n  value: "scmm-pw"')
        assertThat(parseActualYaml()['service']).isEqualTo([nodePort: 9091, type: 'NodePort'])
        assertThat(parseActualYaml()['ingress']).isEqualTo([enabled: true, path: '/', hosts: ['scmm.localhost']])
        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo(
                'helm repo add scm-manager https://packages.scm-manager.org/repository/helm-v2-releases/')
        assertThat(helmCommands.actualCommands[1].trim()).isEqualTo(
                'helm upgrade -i scmm scm-manager/scm-manager-chart --version 2.47.0' +
                        " --values ${temporaryYamlFile} --namespace foo-default --create-namespace")

        def env = getEnvAsMap()
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "${System.getProperty('user.dir')}/scripts/scm-manager/init-scmm.sh" as String)

        assertThat(env['GIT_COMMITTER_NAME']).isEqualTo('Cloudogu')
        assertThat(env['GIT_COMMITTER_EMAIL']).isEqualTo('hello@cloudogu.com')
        assertThat(env['GIT_AUTHOR_NAME']).isEqualTo('Cloudogu')
        assertThat(env['GIT_AUTHOR_EMAIL']).isEqualTo('hello@cloudogu.com')
        assertThat(env['GITOPS_USERNAME']).isEqualTo('foo-gitops')
        assertThat(env['TRACE']).isEqualTo('true')
        assertThat(env['SCMM_URL']).isEqualTo('http://scmm')
        assertThat(env['SCMM_USERNAME']).isEqualTo('scmm-usr')
        assertThat(env['SCMM_PASSWORD']).isEqualTo('scmm-pw')
        assertThat(env['JENKINS_URL']).isEqualTo('http://jenkins')
        assertThat(env['JENKINS_URL_FOR_SCMM']).isEqualTo('http://jenkins4scm')
        assertThat(env['SCMM_URL_FOR_JENKINS']).isEqualTo('http://scmm4jenkins')
        assertThat(env['REMOTE_CLUSTER']).isEqualTo('false')
        assertThat(env['INSTALL_ARGOCD']).isEqualTo('true')
        assertThat(env['SPRING_BOOT_HELM_CHART_COMMIT']).isEqualTo('1.2.3')
        assertThat(env['SPRING_BOOT_HELM_CHART_REPO']).isEqualTo('springBootHelmChartUrl')
        assertThat(env['GITOPS_BUILD_LIB_REPO']).isEqualTo('gitopsBuildLibUrl')
        assertThat(env['CES_BUILD_LIB_REPO']).isEqualTo('cesBuildLibUrl')
        assertThat(env['NAME_PREFIX']).isEqualTo('foo-')
        assertThat(env['INSECURE']).isEqualTo('false')
    }

    @Test
    void 'Sets service and host only if enabled'() {
        config.application['remote'] = true
        config.scmm['ingress'] = ''
        createScmManager().install()

        Map actualYaml = parseActualYaml() as Map

        assertThat(actualYaml).doesNotContainKey('service')
        assertThat(actualYaml).doesNotContainKey('ingress')
    }

    @Test
    void 'Installs only if internal'() {
        config.scmm['internal'] = false
        createScmManager().install()

        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'Prepares repos for air-gapped use'() {
        setupForAirgappedUse()
        
        def response = mockSuccessfulResponse(201)
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)
        
        createScmManager().install()

        assertAirGapped()
    }

    @Test
    void 'Air-gapped: Fails when unable to resolve version of dependencies'() {
        setupForAirgappedUse([:])
        def exception = shouldFail(RuntimeException) {
            createScmManager().install()
        }
        
        assertThat(exception.message).isEqualTo(
                'Unable to determine proper version for dependency grafana (version: 7.3.*) ' +
                        'from repo foo-3rd-party-dependencies/kube-prometheus-stack'
        )
    }

    @Test
    void 'Air-gapped: Prometheus only applied when monitoring active'() {
        config['features']['monitoring']['active'] = false
        createScmManager().install()

        assertThat(scmmRepoProvider.repos['3rd-party-dependencies/kube-prometheus-stack']).isNull()
    }
    
    @Test
    void 'Ignores existing Repos'() {
        setupForAirgappedUse()

        def errorResponse = this.mockErrorResponse(409)
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(errorResponse)

        createScmManager().install()

        assertAirGapped()
    }
    
    @Test
    void 'Ignores existing Permissions'() {
        setupForAirgappedUse()

        def errorResponse = mockErrorResponse(409)
        def successfulResponse = mockSuccessfulResponse(201)

        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(successfulResponse)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(errorResponse)

        createScmManager().install()

        assertAirGapped()
    }
    
    @Test
    void 'Handles failures to SCMM-API for Repos'() {
        setupForAirgappedUse()

        def errorResponse = this.mockErrorResponse(500)

        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(errorResponse)

        def exception = shouldFail(RuntimeException) {
            createScmManager().install()
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
            createScmManager().install()
        }
        assertThat(exception.message).startsWith('Could not create Permission for repo 3rd-party-dependencies/kube-prometheus-stack')
        assertThat(exception.message).contains('foo-gitops')
        assertThat(exception.message).contains(Permission.Role.WRITE.name())
        assertThat(exception.message).contains('500')
    }

    protected void setupForAirgappedUse(Map prometheusChartLock = null) {
        def response = mockSuccessfulResponse(201)
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        Path prometheusSourceChart = Files.createTempDirectory(this.class.getSimpleName())
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
        fileSystemUtils.writeYaml(prometheusChartYaml, prometheusSourceChart.resolve('Chart.yaml').toFile())

        if(prometheusChartLock == null) {
            prometheusChartLock = [
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
        fileSystemUtils.writeYaml(prometheusChartLock, prometheusSourceChart.resolve('Chart.lock').toFile())

        config.application['airGapped'] = true
        config.features['monitoring']['helm']['localFolder'] = prometheusSourceChart.toString()
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
        assertThat(repoCreateArgument.allValues[0].namespace).isEqualTo(ScmManager.NAMESPACE_3RD_PARTY_DEPENDENCIES)
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
    
    protected Map<String, String> getEnvAsMap() {
        commandExecutor.environment.collectEntries { it.split('=') }
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }

    private ScmManager createScmManager() {
        new ScmManager(new Configuration(config), commandExecutor, new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
            Path ret = super.copyToTempDir(filePath)
            temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")).toFile()
            // Path after template invocation
            return ret
            }
        }, scmmRepoProvider, repositoryApi,
        new HelmStrategy(new Configuration(config), helmClient))
    }
}
