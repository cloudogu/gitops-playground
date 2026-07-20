package com.cloudogu.gitops.infrastructure.git.providers.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

@Slf4j
public class ScmManagerUrlResolver {

    private final DeploymentContext context;
    private final ScmManagerConfig scmm;
    private final K8sClient k8s;
    private final NetworkingUtils net;
    private final String servicePrefix;

    private URI cachedClusterBind;

    private final String releaseName = "scmm";

    public ScmManagerUrlResolver(DeploymentContext context,
                                 ScmManagerConfig scmm,
                                 K8sClient k8s,
                                 NetworkingUtils net) {
        this(context, scmm, k8s, net, "");
    }

    public ScmManagerUrlResolver(DeploymentContext context,
                                 ScmManagerConfig scmm,
                                 K8sClient k8s,
                                 NetworkingUtils net,
                                 String servicePrefix) {
        this.context = context;
        this.scmm = scmm;
        this.k8s = k8s;
        this.net = net;
        this.servicePrefix = servicePrefix != null ? servicePrefix : "";
    }

    private Config getConfig() {
        return context.getConfig();
    }

    // ---------- Public API used by ScmManager ----------

    /** Client base …/scm (no trailing slash) */
    public URI clientBase() {
        return noTrailSlash(ensureScm(clientBaseRaw()));
    }

    /** Client API base …/scm/api/ */
    public URI clientApiBase() {
        return withSlash(clientBase()).resolve("api/");
    }

    /** Client repo base …/scm/repo (no trailing slash) */
    public URI clientRepoBase() {
        return noTrailSlash(withSlash(clientBase()).resolve(root() + "/"));
    }

    /** In-cluster base …/scm (no trailing slash) */
    public URI inClusterBase() {
        return noTrailSlash(ensureScm(inClusterBaseRaw()));
    }

    /** In-cluster repo prefix …/scm/repo/[<namePrefix>] */
    public String inClusterRepoPrefix() {
        String prefix = getConfig().getApplication().getNamePrefix() != null ? getConfig().getApplication().getNamePrefix().trim() : "";
        URI base = withSlash(inClusterBase());
        URI url = withSlash(base.resolve(root()));

        return URI.create(url.toString() + prefix).toString();
    }

    /** In-cluster repo URL …/scm/repo/<ns>/<name> */
    public String inClusterRepoUrl(String repoTarget) {
        String repo = repoTarget.trim();
        return noTrailSlash(withSlash(inClusterBase()).resolve(root() + "/" + repo + "/")).toString();
    }

    /** Client repo URL …/scm/repo/<ns>/<name> (no trailing slash) */
    public String clientRepoUrl(String repoTarget) {
        String repo = repoTarget.trim();
        return noTrailSlash(withSlash(clientRepoBase()).resolve(repo + "/")).toString();
    }

    /** …/scm/api/v2/metrics/prometheus */
    public URI prometheusEndpoint() {
        return withSlash(clientBase()).resolve("api/v2/metrics/prometheus");
    }

    // ---------- Base resolution ----------

    private URI clientBaseRaw() {
        if (Boolean.TRUE.equals(scmm.getInternal())) {
            return getConfig().getApplication().getRunningInsideK8s() ? serviceDnsBase() : nodePortBase();
        }
        return externalBase();
    }

    private URI inClusterBaseRaw() {
        return Boolean.TRUE.equals(scmm.getInternal()) ? serviceDnsBase() : externalBase();
    }

    private URI serviceDnsBase() {
        return URI.create("http://" + serviceName() + "." + serviceNamespace() + ".svc.cluster.local");
    }

    private URI externalBase() {
        String url = scmm.getUrl() != null ? scmm.getUrl().trim() : "";
        if (!url.isEmpty()) {
            return URI.create(url);
        }

        String ingress = scmm.getIngress() != null ? scmm.getIngress().trim() : "";
        if (!ingress.isEmpty()) {
            return URI.create("http://" + ingress);
        }
        throw new IllegalArgumentException("Either scmm.url or scmm.ingress must be set when internal=false");
    }

    private URI nodePortBase() {
        if (cachedClusterBind != null) {
            return cachedClusterBind;
        }

        String port = k8s.waitForNodePort(serviceName(), serviceNamespace());
        String host = net.findClusterBindAddress();
        try {
            cachedClusterBind = new URI("http://" + host + ":" + port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct ScmManager node port base URI", e);
        }
        return cachedClusterBind;
    }

    private String serviceName() {
        String prefix = servicePrefix.trim();

        if (!prefix.isEmpty()) {
            return prefix + releaseName;
        }

        return releaseName;
    }

    private String serviceNamespace() {
        String namespace = scmm.getNamespace() != null ? scmm.getNamespace().trim() : "scm-manager";
        String prefix = servicePrefix.trim();

        if (!prefix.isEmpty() && !namespace.startsWith(prefix)) {
            return prefix + namespace;
        }

        return namespace;
    }

    // ---------- Helpers ----------

    private String root() {
        return "repo";
    }

    private static URI ensureScm(URI u) {
        URI us = withSlash(u);
        String path = us.getPath() != null ? us.getPath() : "";
        return path.endsWith("/scm/") ? us : us.resolve("scm/");
    }

    private static URI withSlash(URI u) {
        String s = u.toString();
        return s.endsWith("/") ? u : URI.create(s + "/");
    }

    private static URI noTrailSlash(URI u) {
        String s = u.toString();
        return s.endsWith("/") ? URI.create(s.substring(0, s.length() - 1)) : u;
    }
}
