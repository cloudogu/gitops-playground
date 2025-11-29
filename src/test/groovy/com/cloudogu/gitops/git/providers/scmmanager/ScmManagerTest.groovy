package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.git.providers.scmmanager.api.RepositoryApi
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import okhttp3.internal.http.RealResponseBody
import okio.BufferedSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import retrofit2.Call
import retrofit2.Response

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*
import org.junit.jupiter.api.function.Executable

@ExtendWith(MockitoExtension)
class ScmManagerTest {

    private Config config

    @Mock ScmManagerConfig scmmCfg
    @Mock K8sClient k8s
    @Mock NetworkingUtils net
    @Mock ScmManagerUrlResolver urls
    @Mock ScmManagerApiClient apiClient
    @Mock RepositoryApi repoApi

    @BeforeEach
    void setup() {
        config = new Config(
                application: new Config.ApplicationSchema(
                        insecure: false,
                        namePrefix: "fv40-",
                        runningInsideK8s: true
                )
        )

        lenient().when(scmmCfg.getCredentials()).thenReturn(new Credentials("user","password"))
        lenient().when(scmmCfg.getGitOpsUsername()).thenReturn("gitops-bot")

        lenient().when(urls.inClusterBase()).thenReturn(new URI("http://scmm.ns.svc.cluster.local/scm"))
        lenient().when(urls.inClusterRepoPrefix()).thenReturn("http://scmm.ns.svc.cluster.local/scm/repo/fv40-")
        lenient().when(urls.clientApiBase()).thenReturn(new URI("http://nodeport/scm/api/v2/"))

        lenient().when(apiClient.repositoryApi()).thenReturn(repoApi)
    }

    private ScmManager newSchManager() {
        return new ScmManager(config, scmmCfg, urls, apiClient)
    }

    private static Call<Void> callReturningSuccess(int code) {
        def call = mock(Call)
        when(call.execute()).thenReturn(Response.success(code, null))
        call
    }
    private static Call<Void> callReturningError(int code) {
        def call = mock(Call)
        def body = new RealResponseBody('ignored', 0, mock(BufferedSource))
        when(call.execute()).thenReturn(Response.error(code, body))
        call
    }


    @Test
    void 'createRepository returns true on 201 and false on subsequent 409 for the same repo'() {
        def scmManager = newSchManager()

        def created = callReturningSuccess(201)
        def conflict = callReturningError(409)
        def seen = new HashSet<String>()

        when(repoApi.create(any(Repository), anyBoolean()))
                .thenAnswer(inv -> {
                    Repository r = inv.getArgument(0)
                    if (seen.contains(r.fullRepoName)) return conflict
                    seen.add(r.fullRepoName)
                    return created
                })

        assertTrue(scmManager.createRepository("team/demo", "Demo repo", true))
        assertFalse(scmManager.createRepository("team/demo", "Demo repo", true))  // 409
        assertTrue(scmManager.createRepository("team/other", null, false))        // neuer Name -> 201

        verify(repoApi, times(3)).create(any(Repository), anyBoolean())
    }

    @Test
    void 'setRepositoryPermission maps MAINTAIN to WRITE and handles 201 409'() {
        def scmManager = newSchManager()

        def created = callReturningSuccess(201)
        def conflict = callReturningError(409)
        def seen = new HashSet<String>() // key: ns/name

        when(repoApi.createPermission(anyString(), anyString(), any(Permission)))
                .thenAnswer(inv -> {
                    String namespace = inv.getArgument(0)
                    String repoName = inv.getArgument(1)
                    String key = namespace + "/" + repoName
                    if (seen.contains(key)) return conflict
                    seen.add(key)
                    return created
                })

        assertDoesNotThrow({ ->
            scmManager.setRepositoryPermission("namespace/repo1", "devs", AccessRole.MAINTAIN, Scope.GROUP)
        } as Executable)

        assertDoesNotThrow({ ->
            scmManager.setRepositoryPermission("namespace/repo1", "devs", AccessRole.MAINTAIN, Scope.GROUP)
        } as Executable)
        verify(repoApi, atLeastOnce())
                .createPermission(eq("namespace"), eq("repo1"), argThat { Permission p -> p.groupPermission && p.role == Permission.Role.WRITE })
    }


    @Test
    void 'url, repoPrefix, repoUrl variants, protocol and host come from UrlResolver'() {
        when(urls.inClusterRepoUrl(anyString())).thenAnswer(a -> "http://scmm.ns.svc.cluster.local/scm/repo/" + a.getArgument(0))
        when(urls.clientRepoUrl(anyString())).thenAnswer(a -> "http://nodeport/scm/repo/" + a.getArgument(0))

        def scmManager = newSchManager()

        assertEquals("http://scmm.ns.svc.cluster.local/scm", scmManager.url)
        assertEquals("http://scmm.ns.svc.cluster.local/scm/repo/fv40-", scmManager.repoPrefix())

        assertEquals("http://scmm.ns.svc.cluster.local/scm/repo/team/app",
                scmManager.repoUrl("team/app", RepoUrlScope.IN_CLUSTER))
        assertEquals("http://nodeport/scm/repo/team/app",
                scmManager.repoUrl("team/app", RepoUrlScope.CLIENT))

        assertEquals("http", scmManager.protocol)
        assertEquals("scmm.ns.svc.cluster.local", scmManager.host)
    }

    @Test
    void 'prometheusMetricsEndpoint is delegated to UrlResolver'() {
        when(urls.prometheusEndpoint()).thenReturn(new URI("http://nodeport/scm/api/v2/metrics/prometheus"))
        def scmManager = newSchManager()
        assertEquals(new URI("http://nodeport/scm/api/v2/metrics/prometheus"), scmManager.prometheusMetricsEndpoint())
    }

    // Credentials & GitOps-User
    @Test
    void 'credentials and gitOpsUsername come from ScmManagerConfig'() {
        def scmManager = newSchManager()
        assertEquals("user", scmManager.credentials.username)
        assertEquals("password",  scmManager.credentials.password)
        assertEquals("gitops-bot", scmManager.gitOpsUsername)
    }

}