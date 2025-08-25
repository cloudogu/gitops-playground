package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config

class ScmUrlResolver {
    /** Service-Basis (für SCM-Manager inkl. /scm/, IMMER mit trailing /)
        Basis-URL: intern → Service-URL mit /scm/; extern → konfigurierte URL
     */
    static URI baseUri(Config config) {
        if(config.scmm.internal) {
            return new URI("http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm/")
        }

        def urlString = config.scmm?.url?.strip() ?: ""
        if (!urlString) {
            throw new IllegalArgumentException("config.scmm.url must be set when config.scmm.internal = false")
        }
        def url = URI.create(urlString)
        // trailing slash sicherstellen
        return url.toString().endsWith("/") ? url : URI.create(url.toString() + "/")
    }


    static URI metricsUri(Config config) {
        return baseUri(config).resolve("api/v2/metrics/prometheus")
    }

    static String repoUrl(Config config, String repoNamespaceAndName) {
        return baseUri(config).resolve("repo/${repoNamespaceAndName}").toString()
    }

}
