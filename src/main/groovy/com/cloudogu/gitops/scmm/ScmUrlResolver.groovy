package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config

class ScmUrlResolver {
    // Basis-URL: intern → Service-URL mit /scm; extern → konfigurierte URL
    static URI baseUri(Config cfg) {
        if(cfg.scmm.internal){
            return new URI("http://scmm.${cfg.application.namePrefix}scm-manager.svc.cluster.local/scm/")
        }

        def urlString = cfg.scmm?.url?.strip() ?: ""
        if (!urlString) {
            throw new IllegalArgumentException("config.scmm.url must be set when config.scmm.internal = false")
        }
        def url = URI.create(urlString)
        // trailing slash sicherstellen
        return url.toString().endsWith("/") ? url : URI.create(url.toString() + "/")
    }

    static URI metricsUri(Config cfg) {
        return baseUri(cfg).resolve("api/v2/metrics/prometheus")
    }
}
