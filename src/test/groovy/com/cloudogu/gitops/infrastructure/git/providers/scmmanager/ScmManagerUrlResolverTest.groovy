package com.cloudogu.gitops.infrastructure.git.providers.scmmanager

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension)
class ScmManagerUrlResolverTest {

	private Config config

	@Mock
	private K8sClient k8s

	@Mock
	private NetworkingUtils net

	@BeforeEach
	void setUp() {
		config = new Config(application: new Config.ApplicationSchema(namePrefix: 'fv40-',
			runningInsideK8s: false))
	}

	private ScmManagerUrlResolver resolverWith(Map args = [:], String servicePrefix = 'fv40-') {
		def scmmConfig = new ScmTenantSchema.ScmManagerTenantConfig()
		scmmConfig.internal = (args.containsKey('internal') ? args.internal : true)
		scmmConfig.namespace = (args.containsKey('namespace') ? args.namespace : 'scm-manager')
		scmmConfig.url = (args.containsKey('url') ? args.url : '')
		scmmConfig.ingress = (args.containsKey('ingress') ? args.ingress : '')

		return new ScmManagerUrlResolver(config, scmmConfig, k8s, net, servicePrefix)
	}

	// ---------- Client base & API ----------

	@Test
	void "clientBase(): tenant internal outside K8s uses prefixed NodePort lookup and appends 'scm' only once"() {
		when(k8s.waitForNodePort('fv40-scmm', 'fv40-scm-manager')).thenReturn('30080')
		when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

		def r = resolverWith()
		URI base1 = r.clientBase()
		URI base2 = r.clientBase()

		assertEquals('http://10.0.0.1:30080/scm', base1.toString())
		assertEquals(base1, base2)

		verify(k8s, times(1)).waitForNodePort('fv40-scmm', 'fv40-scm-manager')
		verify(net, times(1)).findClusterBindAddress()
		verifyNoMoreInteractions(k8s, net)
	}

	@Test
	void "clientBase(): central internal outside K8s keeps unprefixed service name and namespace"() {
		when(k8s.waitForNodePort('scmm', 'scm-manager')).thenReturn('30080')
		when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

		def r = resolverWith([:], '')

		assertEquals('http://10.0.0.1:30080/scm', r.clientBase().toString())

		verify(k8s).waitForNodePort('scmm', 'scm-manager')
		verify(net).findClusterBindAddress()
		verifyNoMoreInteractions(k8s, net)
	}

	@Test
	void "clientApiBase(): appends 'api' to the client base"() {
		when(k8s.waitForNodePort('fv40-scmm', 'fv40-scm-manager')).thenReturn('30080')
		when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

		def urlResolver = resolverWith()

		assertEquals('http://10.0.0.1:30080/scm/api/', urlResolver.clientApiBase().toString())
	}

	// ---------- Repo base & URLs ----------

	@Test
	void "clientRepoUrl(): trims repoTarget and removes trailing slash"() {
		when(k8s.waitForNodePort('fv40-scmm', 'fv40-scm-manager')).thenReturn('30080')
		when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

		def urlResolver = resolverWith()

		assertEquals('http://10.0.0.1:30080/scm/repo/ns/project',
			urlResolver.clientRepoUrl('  ns/project  '))
	}

	// ---------- In-cluster base & URLs ----------

	@Test
	void "inClusterBase(): tenant internal uses prefixed service DNS"() {
		config.application.runningInsideK8s = true

		def r = resolverWith()

		assertEquals('http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm',
			r.inClusterBase().toString())
	}

	@Test
	void "inClusterBase(): tenant internal prefixes custom namespace when needed"() {
		config.application.runningInsideK8s = true

		def r = resolverWith(namespace: 'custom-ns')

		assertEquals('http://fv40-scmm.fv40-custom-ns.svc.cluster.local/scm',
			r.inClusterBase().toString())
	}

	@Test
	void "inClusterBase(): tenant internal does not duplicate already prefixed namespace"() {
		config.application.runningInsideK8s = true

		def r = resolverWith(namespace: 'fv40-scm-manager')

		assertEquals('http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm',
			r.inClusterBase().toString())
	}

	@Test
	void "inClusterBase(): central internal uses unprefixed service DNS"() {
		config.application.runningInsideK8s = true

		def r = resolverWith([:], '')

		assertEquals('http://scmm.scm-manager.svc.cluster.local/scm',
			r.inClusterBase().toString())
	}

	@Test
	void "inClusterBase(): external uses external base + 'scm'"() {
		def r = resolverWith(internal: false, url: 'https://fv40-scmm.external')

		assertEquals('https://fv40-scmm.external/scm', r.inClusterBase().toString())
	}

	@Test
	void "inClusterRepoUrl(): builds full tenant in-cluster repo URL without trailing slash"() {
		config.application.runningInsideK8s = true

		def urlResolver = resolverWith()

		assertEquals('http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm/repo/admin/admin',
			urlResolver.inClusterRepoUrl('admin/admin'))
	}

	@Test
	void "inClusterRepoPrefix(): tenant service uses servicePrefix and repo namespace uses application namePrefix"() {
		config.application.runningInsideK8s = true

		def r = resolverWith()

		assertEquals('http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm/repo/fv40-',
			r.inClusterRepoPrefix())
	}

	@Test
	void "inClusterRepoPrefix(): central service stays unprefixed but repo namespace still uses application namePrefix"() {
		config.application.runningInsideK8s = true

		def r = resolverWith([:], '')

		assertEquals('http://scmm.scm-manager.svc.cluster.local/scm/repo/fv40-',
			r.inClusterRepoPrefix())
	}

	@Test
	void "inClusterRepoPrefix(): empty application namePrefix yields base repo path"() {
		config.application.runningInsideK8s = true
		config.application.namePrefix = '   '

		def r = resolverWith([:], '')

		assertEquals('http://scmm.scm-manager.svc.cluster.local/scm/repo/',
			r.inClusterRepoPrefix())
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

		def ex = assertThrows(IllegalArgumentException) {
			r.inClusterBase()
		}

		assertTrue(ex.message.contains('Either scmm.url or scmm.ingress must be set when internal=false'))
	}

	@Test
	void "nodePortBase(): tenant falls back to prefixed default namespace when none provided"() {
		when(k8s.waitForNodePort(eq('fv40-scmm'), eq('fv40-scm-manager'))).thenReturn('30080')
		when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

		def r = resolverWith(namespace: null)

		assertEquals('http://10.0.0.1:30080/scm', r.clientBase().toString())
	}

	@Test
	void "nodePortBase(): central falls back to unprefixed default namespace when none provided"() {
		when(k8s.waitForNodePort(eq('scmm'), eq('scm-manager'))).thenReturn('30080')
		when(net.findClusterBindAddress()).thenReturn('10.0.0.1')

		def r = resolverWith([namespace: null], '')

		assertEquals('http://10.0.0.1:30080/scm', r.clientBase().toString())
	}

	// ---------- helpers behavior ----------

	@Test
	void "ensureScm(): adds 'scm' if missing and keeps it if present"() {
		def r1 = resolverWith(internal: false, url: 'https://fv40-scmm.localhost')
		assertEquals('https://fv40-scmm.localhost/scm', r1.clientBase().toString())

		def r2 = resolverWith(internal: false, url: 'https://fv40-scmm.localhost/scm')
		assertEquals('https://fv40-scmm.localhost/scm', r2.clientBase().toString())
	}

	// ---------- prometheus endpoint ----------

	@Test
	void "prometheusEndpoint(): resolves"() {
		def r = resolverWith(internal: false, url: 'https://fv40-scmm.localhost')

		assertEquals('https://fv40-scmm.localhost/scm/api/v2/metrics/prometheus',
			r.prometheusEndpoint().toString())
	}
}