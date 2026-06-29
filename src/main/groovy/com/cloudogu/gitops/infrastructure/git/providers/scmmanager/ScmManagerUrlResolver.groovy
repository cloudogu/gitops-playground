package com.cloudogu.gitops.infrastructure.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import groovy.util.logging.Slf4j

@Slf4j
class ScmManagerUrlResolver {

	private final Config config
	private final ScmManagerConfig scmm
	private final K8sClient k8s
	private final NetworkingUtils net
	private final String servicePrefix

	private URI cachedClusterBind

	private final String releaseName = 'scmm'

	ScmManagerUrlResolver(Config config,
		ScmManagerConfig scmm,
		K8sClient k8s,
		NetworkingUtils net,
		String servicePrefix = '') {
		this.config = config
		this.scmm = scmm
		this.k8s = k8s
		this.net = net
		this.servicePrefix = servicePrefix
	}

	// ---------- Public API used by ScmManager ----------

	/** Client base …/scm (no trailing slash) */
	URI clientBase() {
		return noTrailSlash(ensureScm(clientBaseRaw()))
	}

	/** Client API base …/scm/api/ */
	URI clientApiBase() {
		return withSlash(clientBase()).resolve('api/')
	}

	/** Client repo base …/scm/repo (no trailing slash) */
	URI clientRepoBase() {
		return noTrailSlash(withSlash(clientBase()).resolve("${root()}/"))
	}

	/** In-cluster base …/scm (no trailing slash) */
	URI inClusterBase() {
		return noTrailSlash(ensureScm(inClusterBaseRaw()))
	}

	/** In-cluster repo prefix …/scm/repo/[<namePrefix>] */
	String inClusterRepoPrefix() {
		def prefix = (config.application.namePrefix ?: '').strip()
		def base = withSlash(inClusterBase())
		def url = withSlash(base.resolve(root()))

		return URI.create(url.toString() + prefix).toString()
	}

	/** In-cluster repo URL …/scm/repo/<ns>/<name> */
	String inClusterRepoUrl(String repoTarget) {
		def repo = repoTarget.strip()
		return noTrailSlash(withSlash(inClusterBase()).resolve("${root()}/${repo}/")).toString()
	}

	/** Client repo URL …/scm/repo/<ns>/<name> (no trailing slash) */
	String clientRepoUrl(String repoTarget) {
		def repo = repoTarget.strip()
		return noTrailSlash(withSlash(clientRepoBase()).resolve("${repo}/")).toString()
	}

	/** …/scm/api/v2/metrics/prometheus */
	URI prometheusEndpoint() {
		return withSlash(clientBase()).resolve('api/v2/metrics/prometheus')
	}

	// ---------- Base resolution ----------

	private URI clientBaseRaw() {
		if (Boolean.TRUE == scmm.internal) return config.application.runningInsideK8s ? serviceDnsBase() : nodePortBase()
		return externalBase()
	}

	private URI inClusterBaseRaw() {
		return scmm.internal ? serviceDnsBase() : externalBase()
	}

	private URI serviceDnsBase() {
		return URI.create("http://${serviceName()}.${serviceNamespace()}.svc.cluster.local")
	}

	private URI externalBase() {
		def url = (scmm.url ?: '').strip()
		if (url) return URI.create(url)

		def ingress = (scmm.ingress ?: '').strip()
		if (ingress) return URI.create("http://${ingress}")
		throw new IllegalArgumentException('Either scmm.url or scmm.ingress must be set when internal=false')
	}

	private URI nodePortBase() {
		if (cachedClusterBind) return cachedClusterBind

		final def port = k8s.waitForNodePort(serviceName(), serviceNamespace())
		final def host = net.findClusterBindAddress()
		cachedClusterBind = new URI("http://${host}:${port}")
		return cachedClusterBind
	}

	private String serviceName() {
		def prefix = servicePrefix.strip()

		if (prefix) {
			return "${prefix}${releaseName}"
		}

		return releaseName
	}

	private String serviceNamespace() {
		def namespace = (scmm.namespace ?: 'scm-manager').strip()
		def prefix = servicePrefix.strip()

		if (prefix && !namespace.startsWith(prefix)) {
			return "${prefix}${namespace}"
		}

		return namespace
	}

	// ---------- Helpers ----------

	private String root() {
		return 'repo'
	}

	private static URI ensureScm(URI u) {
		def us = withSlash(u)
		def path = us.path ?: ''
		return path.endsWith('/scm/') ? us : us.resolve('scm/')
	}

	private static URI withSlash(URI u) {
		def s = u.toString()
		return s.endsWith('/') ? u : URI.create(s + '/')
	}

	private static URI noTrailSlash(URI u) {
		def s = u.toString()
		return s.endsWith('/') ? URI.create(s[0..-2]) : u
	}
}