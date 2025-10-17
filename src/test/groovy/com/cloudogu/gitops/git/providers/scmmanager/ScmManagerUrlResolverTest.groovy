package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.ScmTenantSchema
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

@ExtendWith(MockitoExtension.class)
class ScmManagerUrlResolverTest {
    private Config config

    @Mock
    private K8sClient k8s
    @Mock
    private NetworkingUtils net

    @BeforeEach
    void setUp() {
        config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'fv40-',
                        runningInsideK8s: false
                )
        )
    }

    private ScmManagerUrlResolver resolverWith(Map args = [:]) {
        def scmmCfg = new ScmTenantSchema.ScmManagerTenantConfig()
        scmmCfg.internal = (args.containsKey('internal') ? args.internal : true)
        scmmCfg.namespace = (args.containsKey('namespace') ? args.namespace : "scm-manager")
        scmmCfg.rootPath = (args.containsKey('rootPath') ? args.rootPath : "repo")
        scmmCfg.url = (args.containsKey('url') ? args.url : "")
        scmmCfg.ingress = (args.containsKey('ingress') ? args.ingress : "")

        return new ScmManagerUrlResolver(config, scmmCfg, k8s, net)
    }

    // ---------- Client base & API ----------
    @Test
    @DisplayName("clientBase(): internal + outside K8s uses NodePort and appends '/scm' (no trailing slash) and only resolves NodePort once")
    void clientBase_internalOutsideK8s_usesNodePortWithScm_andIsEffectivelyCached() {
        when(k8s.waitForNodePort(eq('scmm'), any())).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1")

        def r = resolverWith()
        URI base1 = r.clientBase()
        URI base2 = r.clientBase()

        assertEquals("http://10.0.0.1:30080/scm", base1.toString())
        assertEquals(base1, base2)

        verify(k8s, times(1)).waitForNodePort("scmm", "scm-manager")
        verify(net, times(1)).findClusterBindAddress()
        verifyNoMoreInteractions(k8s, net)
    }

    @Test
    @DisplayName("clientApiBase(): appends 'api/' to the client base")
    void clientApiBase_appendsApiSlash() {
        when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1")

        var r = resolverWith()
        assertEquals("http://10.0.0.1:30080/scm/api/", r.clientApiBase().toString())
    }

    // ---------- Repo base & URLs ----------
    @Test
    @DisplayName("clientRepoUrl(): trims repoTarget and removes trailing slash")
    void clientRepoUrl_trimsAndRemovesTrailingSlash() {
        when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1")

        var r = resolverWith()
        assertEquals("http://10.0.0.1:30080/scm/repo/ns/project",
                r.clientRepoUrl("  ns/project  "))
    }

    // ---------- In-cluster base & URLs ----------
    @Test
    @DisplayName("inClusterBase(): internal uses service DNS 'http://scmm.<ns>.svc.cluster.local/scm'")
    void inClusterBase_internal_usesServiceDns() {
        def r = resolverWith(namespace: "custom-ns", internal: true)
        assertEquals("http://scmm.custom-ns.svc.cluster.local/scm", r.inClusterBase().toString())
    }


    @Test
    @DisplayName("inClusterBase(): external uses external base + '/scm'")
    void inClusterBase_external_usesExternalBase() {
        var r = resolverWith(internal: false, url: "https://scmm.external")
        assertEquals("https://scmm.external/scm", r.inClusterBase().toString())
    }


    @Test
    @DisplayName("inClusterRepoUrl(): builds full in-cluster repo URL without trailing slash")
    void inClusterRepoUrl_buildsUrl() {

        var r = resolverWith()
        assertEquals("http://scmm.scm-manager.svc.cluster.local/scm/repo/admin/admin",
                r.inClusterRepoUrl("admin/admin"))
    }

    @Test
    @DisplayName("inClusterRepoPrefix(): includes configured namePrefix (empty prefix yields base path)")
    void inClusterRepoPrefix_includesNamePrefixOrBase() {
        // with non-empty namePrefix
        config.application.namePrefix = 'fv40-'
        def r1 = resolverWith()
        assertEquals('http://scmm.scm-manager.svc.cluster.local/scm/repo/fv40-', r1.inClusterRepoPrefix())

        // with empty/blank namePrefix
        config.application.namePrefix = '   '
        def r2 = resolverWith()
        assertEquals('http://scmm.scm-manager.svc.cluster.local/scm/repo', r2.inClusterRepoPrefix())
    }

    // ---------- externalBase selection & error ----------
    @Test
    @DisplayName("externalBase(): prefers 'url' over 'ingress'")
    void externalBase_prefersUrlOverIngress() {
        def r = resolverWith(internal: false, url: 'https://scmm.external', ingress: 'ingress.example.org')
        assertEquals('https://scmm.external/scm', r.inClusterBase().toString())
    }

    @Test
    @DisplayName("externalBase(): uses 'ingress' when 'url' is missing")
    void externalBase_usesIngressWhenUrlMissing() {
        def r = resolverWith(internal: false, url: null, ingress: 'ingress.example.org')
        assertEquals('http://ingress.example.org/scm', r.inClusterBase().toString())
    }

    @Test
    @DisplayName("externalBase(): throws when neither 'url' nor 'ingress' is set")
    void externalBase_throwsWhenBothMissing() {
        def r = resolverWith(internal: false, url: null, ingress: null)
        def ex = assertThrows(IllegalArgumentException) { r.inClusterBase() }
        assertTrue(ex.message.contains('Either scmm.url or scmm.ingress must be set when internal=false'))
    }


    @Test
    @DisplayName("nodePortBase(): falls back to default namespace 'scm-manager' when none provided")
    void nodePortBase_usesDefaultNamespaceWhenMissing() {
        when(k8s.waitForNodePort(eq('scmm'), eq('scm-manager'))).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

        def r = resolverWith(namespace: null)
        assertEquals('http://10.0.0.1:30080/scm', r.clientBase().toString())
    }

    // ---------- helpers behavior ----------
    @Test
    @DisplayName("ensureScm(): adds '/scm' if missing and keeps it if present")
    void ensureScm_addsOrKeeps() {
        def r1 = resolverWith(internal: false, url: 'https://scmm.localhost')
        assertEquals('https://scmm.localhost/scm', r1.clientBase().toString())
    }


    // ---------- prometheus endpoint ----------

    @Test
    @DisplayName("prometheusEndpoint(): resolves to '/scm/api/v2/metrics/prometheus'")
    void prometheusEndpoint_isUnderApiV2() {
        def r = resolverWith(internal: false, url: 'https://scmm.localhost')
        assertEquals('https://scmm.localhost/scm/api/v2/metrics/prometheus', r.prometheusEndpoint().toString())
    }
}
