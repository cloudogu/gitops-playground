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
                ),
                scm: new ScmTenantSchema(
                        scmManager: new ScmTenantSchema.ScmManagerTenantConfig(
                                releaseName: 'scmm',
                        )
                )
        )
    }

    private ScmManagerUrlResolver resolverWith(Map args = [:]) {
        def scmmCofig = new ScmTenantSchema.ScmManagerTenantConfig()
        scmmCofig.internal = (args.containsKey('internal') ? args.internal : true)
        scmmCofig.namespace = (args.containsKey('namespace') ? args.namespace : "scm-manager")
        scmmCofig.rootPath = (args.containsKey('rootPath') ? args.rootPath : "repo")
        scmmCofig.url = (args.containsKey('url') ? args.url : "")
        scmmCofig.ingress = (args.containsKey('ingress') ? args.ingress : "")

        return new ScmManagerUrlResolver(config, scmmCofig, k8s, net)
    }

    // ---------- Client base & API ----------
    @Test
    void "clientBase(): internal + outside K8s uses NodePort and appends 'scm' (no trailing slash) and only resolves NodePort once"() {
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
    void "clientApiBase(): appends 'api' to the client base"() {
        when(k8s.waitForNodePort(config.scm.scmManager.releaseName, "scm-manager")).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1")

        var urlResolver = resolverWith()
        assertEquals("http://10.0.0.1:30080/scm/api/", urlResolver.clientApiBase().toString())
    }

    // ---------- Repo base & URLs ----------
    @Test
    void "clientRepoUrl(): trims repoTarget and removes trailing slash"() {
        when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1")

        var urlResolver = resolverWith()
        assertEquals("http://10.0.0.1:30080/scm/repo/ns/project",
                urlResolver.clientRepoUrl("  ns/project  "))
    }

    // ---------- In-cluster base & URLs ----------
    @Test
    void "inClusterBase(): internal uses service DNS "() {
        def r = resolverWith(namespace: "custom-ns", internal: true)
        assertEquals("http://scmm.custom-ns.svc.cluster.local/scm", r.inClusterBase().toString())
    }


    @Test
    void "inClusterBase(): external uses external base + 'scm'"() {
        var r = resolverWith(internal: false, url: "https://scmm.external")
        assertEquals("https://scmm.external/scm", r.inClusterBase().toString())
    }


    @Test
    void "inClusterRepoUrl(): builds full in-cluster repo URL without trailing slash"() {
        var urlResolver = resolverWith()
        assertEquals("http://scmm.scm-manager.svc.cluster.local/scm/repo/admin/admin",
                urlResolver.inClusterRepoUrl("admin/admin"))
    }

    @Test
    void "inClusterRepoPrefix(): includes configured namePrefix (empty prefix yields base path)"() {
        // with non-empty namePrefix
        config.application.namePrefix = 'fv40-'
        def r1 = resolverWith()
        assertEquals('http://scmm.scm-manager.svc.cluster.local/scm/repo/fv40-', r1.inClusterRepoPrefix())

        // with empty/blank namePrefix
        config.application.namePrefix = '   '
        def r2 = resolverWith()
        assertEquals('http://scmm.scm-manager.svc.cluster.local/scm/repo/', r2.inClusterRepoPrefix())
    }

    // ---------- externalBase selection & error ----------
    @Test
    void "externalBase(): prefers 'url' over 'ingress'"() {
        def r = resolverWith(internal: false, url: 'https://scmm.external', ingress: 'ingress.example.org')
        assertEquals('https://scmm.external/scm', r.inClusterBase().toString())
    }

    @Test
    void "externalBase(): uses 'ingress' when 'url' is missing"() {
        def r = resolverWith(internal: false, url: null, ingress: 'ingress.example.org')
        assertEquals('http://ingress.example.org/scm', r.inClusterBase().toString())
    }

    @Test
    void "externalBase(): throws when neither 'url' nor 'ingress' is set"() {
        def r = resolverWith(internal: false, url: null, ingress: null)
        def ex = assertThrows(IllegalArgumentException) { r.inClusterBase() }
        assertTrue(ex.message.contains('Either scmm.url or scmm.ingress must be set when internal=false'))
    }


    @Test
    void "nodePortBase(): falls back to default namespace 'scm-manager' when none provided"() {
        when(k8s.waitForNodePort(eq('scmm'), eq('scm-manager'))).thenReturn("30080")
        when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

        def r = resolverWith(namespace: null)
        assertEquals('http://10.0.0.1:30080/scm', r.clientBase().toString())
    }

    // ---------- helpers behavior ----------
    @Test
    void "ensureScm(): adds 'scm' if missing and keeps it if present"() {
        def r1 = resolverWith(internal: false, url: 'https://scmm.localhost')
        assertEquals('https://scmm.localhost/scm', r1.clientBase().toString())
    }


    // ---------- prometheus endpoint ----------
    @Test
    void "prometheusEndpoint(): resolves "() {
        def r = resolverWith(internal: false, url: 'https://scmm.localhost')
        assertEquals('https://scmm.localhost/scm/api/v2/metrics/prometheus', r.prometheusEndpoint().toString())
    }
}