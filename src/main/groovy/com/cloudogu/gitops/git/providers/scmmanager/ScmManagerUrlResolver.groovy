package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j

@Slf4j
class ScmManagerUrlResolver {

    private final Config config
    private final ScmManagerConfig scmm
    private final K8sClient k8s
    private final NetworkingUtils net

    private final String releaseName = 'scmm'
    private URI cachedClusterBind

    ScmManagerUrlResolver(Config config, ScmManagerConfig scmm, K8sClient k8s, NetworkingUtils net) {
        this.config = config
        this.scmm = scmm
        this.k8s = k8s
        this.net = net
    }

    // ---------- Public API used by ScmManager ----------

    /** Client base …/scm (no trailing slash) */
    URI clientBase() { noTrailSlash(ensureScm(clientBaseRaw())) }

    /** Client API base …/scm/api/ */
    URI clientApiBase() { withSlash(clientBase()).resolve("api/") }

    /** Client repo base …/scm/<root> (no trailing slash) */
    URI clientRepoBase() { noTrailSlash(withSlash(clientBase()).resolve("${root()}/")) }



    /** In-cluster base …/scm (no trailing slash) */
    URI inClusterBase() { noTrailSlash(ensureScm(inClusterBaseRaw())) }

    /** In-cluster repo prefix …/scm/<root>/[<namePrefix>] */
    String inClusterRepoPrefix(boolean includeNamePrefix) {
        def prefix = (config.application.namePrefix ?: "").strip()
        def base = withSlash(inClusterBase())
        def url = withSlash(base.resolve(root()))
        includeNamePrefix && prefix ? noTrailSlash(URI.create(url.toString() + prefix)).toString()
                : url.toString()
    }

    /** In-cluster repo URL …/scm/<root>/<ns>/<name> */
    String inClusterRepoUrl(String repoTarget) {
        def repo = (repoTarget ?: "").strip()
        noTrailSlash(withSlash(inClusterBase()).resolve("${root()}/${repo}/")).toString()
    }

    /** Client repo URL …/scm/<root>/<ns>/<name> (no trailing slash) */
    String clientRepoUrl(String repoTarget) {
        def repo = (repoTarget ?: "").strip()
        noTrailSlash(withSlash(clientRepoBase()).resolve("${repo}/")).toString()
    }

    /** …/scm/api/v2/metrics/prometheus */
    URI prometheusEndpoint() { withSlash(clientBase()).resolve("api/v2/metrics/prometheus") }

    // ---------- Base resolution ----------

    private URI clientBaseRaw() {
        if (Boolean.TRUE == scmm.internal)
            return config.application.runningInsideK8s ? serviceDnsBase() : nodePortBase()
        return externalBase()
    }

    private URI inClusterBaseRaw() {
        return scmm.internal ? serviceDnsBase() : externalBase()
    }

    private URI serviceDnsBase() {
        def ns = (scmm.namespace ?: "scm-manager").trim()
        URI.create("http://scmm.${ns}.svc.cluster.local")
    }

    private URI externalBase() {
        def url = (scmm.url ?: "").strip()
        if (url) return URI.create(url)
        def ingress = (scmm.ingress ?: "").strip()
        if (ingress) return URI.create("http://${ingress}")
        throw new IllegalArgumentException("Either scmm.url or scmm.ingress must be set when internal=false")
    }

    private URI nodePortBase() {
        if (cachedClusterBind) return cachedClusterBind
        final def port = k8s.waitForNodePort(releaseName, scmm.namespace)
        final def host = net.findClusterBindAddress()
        cachedClusterBind = new URI("http://${host}:${port}")
        return cachedClusterBind
    }

    // ---------- Helpers ----------

    private String root() {
        (scmm.rootPath ?: "repo").strip()
    }

    private static URI ensureScm(URI u) {
        def us = withSlash(u)
        def path = us.path ?: ""
        path.endsWith("/scm/") ? us : us.resolve("scm/")
    }

    private static URI withSlash(URI u) {
        def s = u.toString()
        s.endsWith('/') ? u : URI.create(s + '/')
    }

    private static URI noTrailSlash(URI u) {
        def s = u.toString()
        s.endsWith('/') ? URI.create(s.substring(0, s.length() - 1)) : u
    }
}
