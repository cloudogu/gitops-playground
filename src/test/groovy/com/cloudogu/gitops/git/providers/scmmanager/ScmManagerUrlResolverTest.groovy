package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.ScmCentralSchema
import com.cloudogu.gitops.features.git.config.ScmTenantSchema
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScmManagerUrlResolverTest {
    private Config config;

    private ScmManagerConfig scmmConfig

    @Mock
    private K8sClient k8s;
    @Mock
    private NetworkingUtils net;

    @BeforeEach
    void setUp() {
        config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'fv40-',
                        runningInsideK8s: false
                )
        )

        scmmConfig = new ScmCentralSchema.ScmManagerCentralConfig(
                internal: true,
                namespace: "scm-manager",
                rootPath: "repo"
        )

    }

    private ScmManagerUrlResolver newResolver() {
        return new ScmManagerUrlResolver(config, scmmConfig, k8s, net);
    }

    // ---------- Client base & API ----------
    @Test
    @DisplayName("clientBase(): internal + outside K8s uses NodePort and appends '/scm' (no trailing slash) and only resolves NodePort once")
    void clientBase_internalOutsideK8s_usesNodePortWithScm_andIsEffectivelyCached() {
        when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080");
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

        var r = newResolver();
        URI base1 = r.clientBase();
        URI base2 = r.clientBase();

        assertEquals("http://10.0.0.1:30080/scm", base1.toString());
        assertEquals(base1, base2);

        verify(k8s, times(1)).waitForNodePort("scmm", "scm-manager");
        verify(net, times(1)).findClusterBindAddress();
        verifyNoMoreInteractions(k8s, net);
    }

    @Test
    @DisplayName("clientApiBase(): appends 'api/' to the client base")
    void clientApiBase_appendsApiSlash() {
        when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080");
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

        var r = newResolver();
        assertEquals("http://10.0.0.1:30080/scm/api/", r.clientApiBase().toString());
    }

    // ---------- Repo base & URLs ----------
    @Test
    @DisplayName("clientRepoUrl(): trims repoTarget and removes trailing slash")
    void clientRepoUrl_trimsAndRemovesTrailingSlash() {
        when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080");
        when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

        var r = newResolver();
        assertEquals("http://10.0.0.1:30080/scm/repo/ns/project",
                r.clientRepoUrl("  ns/project  "));
    }

    // ---------- In-cluster base & URLs ----------
    @Test
    @DisplayName("inClusterBase(): internal uses service DNS 'http://scmm.<ns>.svc.cluster.local/scm'")
    void inClusterBase_internal_usesServiceDns() {
        scmmConfig.namespace = "custom-ns";

        var r = newResolver();
        assertEquals("http://scmm.custom-ns.svc.cluster.local/scm", r.inClusterBase().toString());
    }

    @Test
    @DisplayName("inClusterBase(): external uses external base + '/scm'")
    void inClusterBase_external_usesExternalBase() {
        scmmConfig.internal = false
        scmmConfig.url = "https://scmm.external";

        var r = newResolver();
        assertEquals("https://scmm.external/scm", r.inClusterBase().toString());
    }
}
