package com.cloudogu.gitops.application.orchestration

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.Mockito.mock

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests
import com.cloudogu.gitops.testhelper.git.GitlabMock
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.utils.NetworkingUtils

import org.junit.jupiter.api.Test

class GitHandlerTest {

	private static Config config(Map overrides = [:]) {
		Map base = [application: [namePrefix: ''],
		            scm        : [scmProviderType: ScmProviderType.SCM_MANAGER,
		                          scmManager     : [internal: true],
		                          gitlab         : [url: '']],
		            multiTenant: [scmManager          : [url: ''],
		                          gitlab              : [url: ''],
		                          useDedicatedInstance: false]]

		Map merged = deepMerge(base, overrides)
		return new Config().fromMap(merged)
	}

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
		return new GitHandler(new ContextBuilder(cfg).build(),
			mock(K8sClient),
			mock(NetworkingUtils))
	}

	// ---------- validate() ------------------------------------------------------------

	@Test
	void 'validate(): ScmManager external url sets internal=false and urlForJenkins equals url'() {
		def cfg = config([application: [namePrefix: 'fv40-'],
		                  scm        : [scmManager: [url     : 'https://scmm.example.com/scm',
		                                             internal: true]]])

		def gh = handler(cfg)

		gh.validate()

		assertFalse(cfg.scm.scmManager.internal)
		assertEquals('https://scmm.example.com/scm', cfg.scm.scmManager.urlForJenkins)
	}

	@Test
	void 'validate(): GitLab chosen, provider switched, scmm nulled, missing PAT or parentGroupId throws'() {
		def cfg = config([scm: [gitlab: [url: 'https://gitlab.example.com']]])

		def gh = handler(cfg)

		def ex = assertThrows(RuntimeException) {
			gh.validate()
		}

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

		def ex = assertThrows(IllegalStateException) {
			gitHandler.getResourcesScm()
		}

		assertTrue(ex.message.contains('No SCM provider'))
	}

	// ---------- prepareProviders(): SCM_MANAGER ---------------------------------------

	@Test
	void 'prepareProviders(): ScmManager tenant-only creates tenant provider only'() {
		def cfg = new Config().fromMap([scm        : [scmManager: [internal: true],
		                                              gitlab    : [url: '']],
		                                multiTenant: [useDedicatedInstance: false]])

		def tenant = new ScmManagerProviderMock()
		def gitHandler = new GitHandlerForTests(cfg, tenant)

		gitHandler.prepareProviders()

		assertEquals('scm-manager', cfg.scm.scmManager.namespace)

		assertSame(tenant, gitHandler.tenant)
		assertNull(gitHandler.central)
		assertSame(tenant, gitHandler.getResourcesScm())
	}

	@Test
	void 'prepareProviders(): ScmManager tenant-only does not create repositories'() {
		def cfg = new Config().fromMap([scm        : [scmManager: [internal: true],
		                                              gitlab    : [url: '']],
		                                multiTenant: [useDedicatedInstance: false]])

		def tenant = new ScmManagerProviderMock()
		def gitHandler = new GitHandlerForTests(cfg, tenant)

		gitHandler.prepareProviders()

		assertTrue(tenant.createdRepos.isEmpty())
	}

	@Test
	void 'prepareProviders(): ScmManager dedicated creates tenant and central providers'() {
		def cfg = config([application: [namePrefix: 'fv40-'],
		                  scm        : [scmProviderType: ScmProviderType.SCM_MANAGER,
		                                scmManager     : [internal: true],
		                                gitlab         : [url: '']],
		                  multiTenant: [useDedicatedInstance: true,
		                                scmManager          : [url: ''],
		                                gitlab              : [url: '']]])

		def tenant = new ScmManagerProviderMock(namePrefix: 'fv40-')
		def central = new ScmManagerProviderMock(namePrefix: 'fv40-')
		def gitHandler = new GitHandlerForTests(cfg, tenant, central)

		gitHandler.prepareProviders()

		assertSame(tenant, gitHandler.tenant)
		assertSame(central, gitHandler.central)
		assertSame(central, gitHandler.getResourcesScm())
	}

	@Test
	void 'prepareProviders(): ScmManager dedicated does not create repositories'() {
		def cfg = config([application: [namePrefix: 'fv40-'],
		                  scm        : [scmProviderType: ScmProviderType.SCM_MANAGER,
		                                scmManager     : [internal: true],
		                                gitlab         : [url: '']],
		                  multiTenant: [useDedicatedInstance: true,
		                                scmManager          : [url: ''],
		                                gitlab              : [url: '']]])

		def tenant = new ScmManagerProviderMock(namePrefix: 'fv40-')
		def central = new ScmManagerProviderMock(namePrefix: 'fv40-')
		def gitHandler = new GitHandlerForTests(cfg, tenant, central)

		gitHandler.prepareProviders()

		assertTrue(tenant.createdRepos.isEmpty())
		assertTrue(central.createdRepos.isEmpty())
	}

	// ---------- prepareProviders(): GITLAB -------------------------------------------

	@Test
	void 'prepareProviders(): Gitlab dedicated creates tenant and central providers'() {
		def cfg = config([application: [namePrefix: 'fv40-'],
		                  scm        : [scmProviderType: ScmProviderType.GITLAB,
		                                gitlab         : [url          : 'https://gitlab.example.com',
		                                                  password     : 'pat',
		                                                  parentGroupId: 123],
		                                scmManager     : [internal: true]],
		                  multiTenant: [useDedicatedInstance: true,
		                                gitlab              : [url          : 'https://gitlab.example.com',
		                                                       password     : 'pat2',
		                                                       parentGroupId: 456],
		                                scmManager          : [url: '']]])

		def tenant = new GitlabMock(base: new URI(cfg.scm.gitlab.url),
			namePrefix: 'fv40-')

		def central = new GitlabMock(base: new URI(cfg.multiTenant.gitlab.url),
			namePrefix: 'fv40-')

		def gitHandler = new GitHandlerForTests(cfg, tenant, central)

		gitHandler.prepareProviders()

		assertSame(tenant, gitHandler.tenant)
		assertSame(central, gitHandler.central)
		assertSame(central, gitHandler.getResourcesScm())
	}

	@Test
	void 'prepareProviders(): Gitlab dedicated does not create repositories'() {
		def cfg = config([application: [namePrefix: 'fv40-'],
		                  scm        : [scmProviderType: ScmProviderType.GITLAB,
		                                gitlab         : [url          : 'https://gitlab.example.com',
		                                                  password     : 'pat',
		                                                  parentGroupId: 123],
		                                scmManager     : [internal: true]],
		                  multiTenant: [useDedicatedInstance: true,
		                                gitlab              : [url          : 'https://gitlab.example.com',
		                                                       password     : 'pat2',
		                                                       parentGroupId: 456],
		                                scmManager          : [url: '']]])

		def tenant = new GitlabMock(base: new URI(cfg.scm.gitlab.url),
			namePrefix: 'fv40-')

		def central = new GitlabMock(base: new URI(cfg.multiTenant.gitlab.url),
			namePrefix: 'fv40-')

		def gitHandler = new GitHandlerForTests(cfg, tenant, central)

		gitHandler.prepareProviders()

		assertTrue(tenant.createdRepos.isEmpty())
		assertTrue(central.createdRepos.isEmpty())
	}
}