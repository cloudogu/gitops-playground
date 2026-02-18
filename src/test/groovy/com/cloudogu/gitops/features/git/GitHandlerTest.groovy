package com.cloudogu.gitops.features.git

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.Mockito.*

import org.junit.jupiter.api.Test

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.GitlabMock
import com.cloudogu.gitops.utils.git.ScmManagerMock

class GitHandlerTest {

    private static Config config(Map overrides = [:]) {
        Map base = [
                application: [
                        namePrefix: ''
                ],
                scm        : [
                        scmProviderType: ScmProviderType.SCM_MANAGER,   // default
                        scmManager     : [
                                internal: true
                        ],
                        gitlab         : [
                                url: ''
                        ]
                ],
                multiTenant: [
                        scmManager          : [ url: '' ],
                        gitlab              : [ url: '' ],
                        useDedicatedInstance: false
                ]
        ]
        Map merged = deepMerge(base, overrides)
        return new Config().fromMap(merged)
    }

    /** simple deep merge for nested maps */
    @SuppressWarnings('unchecked')
    private static Map deepMerge(Map left, Map right) {
        Map out = [:] + left
        right.each { k, v ->
            if (v instanceof Map && left[k] instanceof Map) {
                out[k] = deepMerge((Map) left[k], (Map) v)
            } else {
                out[k] = v
            }
        }
        return out
    }

    private static GitHandler handler(Config cfg) {
        return new GitHandler(
                cfg,
                mock(HelmStrategy),
                mock(FileSystemUtils),
                mock(K8sClient),
                mock(NetworkingUtils)
        )
    }

    // ---------- validate() ------------------------------------------------------------

    @Test
    void 'validate(): ScmManager external url sets internal=false and urlForJenkins equals url'() {
        def cfg = config([
                application: [namePrefix: 'fv40-'],
                scm: [
                        scmManager: [url: 'https://scmm.example.com/scm', internal: true]
                ]
        ])
        def gh = handler(cfg)

        gh.validate()

        assertFalse(cfg.scm.scmManager.internal)
        assertEquals('https://scmm.example.com/scm', cfg.scm.scmManager.urlForJenkins)
    }


    @Test
    void 'validate(): GitLab chosen,  provider switched, scmm nulled, missing PAT or parentGroupId throws'() {
        def cfg = config([
                scm: [
                        gitlab: [url: 'https://gitlab.example.com']
                ]
        ])
        def gh = handler(cfg)

        def ex = assertThrows(RuntimeException) { gh.validate() }
        assertTrue(ex.message.toLowerCase().contains('gitlab'))
        assertEquals(ScmProviderType.GITLAB, cfg.scm.scmProviderType)
        assertNull(cfg.scm.scmManager)
    }

    // ---------- getResourcesScm() -----------------------------------------------------

    @Test
    void 'getResourcesScm(): central wins over tenant'() {
        def cfg = config()
        def gitHandler = handler(cfg)

        gitHandler.tenant = mock(GitProvider, 'tenant')
        gitHandler.central = mock(GitProvider, 'central')

        assertSame(gitHandler.central, gitHandler.getResourcesScm())
    }

    @Test
    void 'getResourcesScm(): tenant returned when central absent, throws when none'() {
        def cfg = config()
        def gitHandler = handler(cfg)

        gitHandler.tenant = mock(GitProvider)
        assertSame(gitHandler.tenant, gitHandler.getResourcesScm())

        gitHandler.tenant = null
        def ex = assertThrows(IllegalStateException) { gitHandler.getResourcesScm() }
        assertTrue(ex.message.contains('No SCM provider'))
    }

    // ---------- enable(): SCM_MANAGER tenant only ------------------------------------
    @Test
    void 'ScmManager tenant-only: tenant gets 1 repository'() {
        def cfg = new Config().fromMap([
                scm:[scmManager:[internal:true], gitlab:[url:'']],
                multiTenant:[useDedicatedInstance:false]
        ])

        def tenant = new ScmManagerMock()
        def gitHandler = new GitHandlerForTests(cfg, tenant)

        gitHandler.enable()

        assertEquals('scm-manager', cfg.scm.scmManager.namespace)

        assertTrue(tenant.createdRepos.contains('argocd/cluster-resources'))
        assertEquals(1, tenant.createdRepos.size())

        // No central provider in tenant-only scenario
        assertNull(gitHandler.getCentral())
    }

    @Test
    void 'ScmManager dedicated: central gets 1 repo, tenant gets 1 repo'() {
        def cfg = config([
                application: [namePrefix: 'fv40-'],
                scm        : [
                        scmProviderType: ScmProviderType.SCM_MANAGER,
                        scmManager     : [internal: true],
                        gitlab         : [url: '']
                ],
                multiTenant: [
                        useDedicatedInstance: true,
                        scmManager: [url: ''],
                        gitlab    : [url: '']
                ]
        ])

        def tenant  = new ScmManagerMock(namePrefix: 'fv40-')
        def central = new ScmManagerMock(namePrefix: 'fv40-')
        def gitHandler = new GitHandlerForTests(cfg, tenant, central)

        gitHandler.enable()

        // Central: argocd/cluster-resources
        assertTrue(central.createdRepos.contains('fv40-argocd/cluster-resources'))
        assertEquals(1, central.createdRepos.size())

        // Tenant: argocd/cluster-resources
        assertTrue(tenant.createdRepos.contains('fv40-argocd/cluster-resources'))
        assertEquals(1, tenant.createdRepos.size())
    }

    @Test
    void 'Gitlab dedicated: same layout as ScmManager dedicated'() {
        def cfg = config([
                application: [namePrefix: 'fv40-'],
                scm        : [
                        scmProviderType: ScmProviderType.GITLAB,
                        gitlab         : [url: 'https://gitlab.example.com', password: 'pat', parentGroupId: 123],
                        scmManager     : [internal: true]
                ],
                multiTenant: [
                        useDedicatedInstance: true,
                        gitlab: [url: 'https://gitlab.example.com', password: 'pat2', parentGroupId: 456],
                        scmManager: [url: '']
                ]
        ])

        // Assumes your GitlabMock has a similar contract to ScmManagerMock (collects createdRepos)
        def tenant  = new GitlabMock(base: new URI(cfg.scm.gitlab.url),                 namePrefix: 'fv40-')
        def central = new GitlabMock(base: new URI(cfg.multiTenant.gitlab.url),         namePrefix: 'fv40-')
        def gitHandler = new GitHandlerForTests(cfg, tenant, central)

        gitHandler.enable()

        // Central: argocd/cluster-resources
        assertTrue(central.createdRepos.contains('fv40-argocd/cluster-resources'))
        assertEquals(1, central.createdRepos.size())

        // Tenant: argocd/cluster-resources
        assertTrue(tenant.createdRepos.contains('fv40-argocd/cluster-resources'))
        assertEquals(1, tenant.createdRepos.size())
    }

    @Test
    void 'withOrgPrefix helper behaves as expected'() {
        assertEquals('argocd/argocd', GitHandler.withOrgPrefix('', 'argocd/argocd'))
        assertEquals('argocd/argocd', GitHandler.withOrgPrefix(null, 'argocd/argocd'))
        assertEquals('fv40-argocd/argocd', GitHandler.withOrgPrefix('fv40-', 'argocd/argocd'))
    }


}
