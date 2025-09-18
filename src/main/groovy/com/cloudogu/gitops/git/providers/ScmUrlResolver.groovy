package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.util.ScmProviderType

class ScmUrlResolver {
    /**
     * Returns the tenant/namespace base URL **without** a trailing slash.
     *
     * Why no trailing slash?
     * - Callers often append further segments (e.g., "/repo/<ns>/<name>" or ".git").
     *   Returning a slash here easily leads to double slashes ("//") or brittle
     *   template logic.
     */
    static String tenantBaseUrl(Config config) {
        switch (config.scm.scmProviderType) {
            case ScmProviderType.SCM_MANAGER:
                // scmmBaseUri ends with /scm/
                return scmmBaseUri(config).resolve("${config.scmm.rootPath}/${config.application.namePrefix}").toString()
            case ScmProviderType.GITLAB:
                // for GitLab, do not append /scm/
                return externalHost(config).resolve("${config.application.namePrefix}${config.scm.rootPath}").toString()
            default:
                throw new IllegalArgumentException("Unknown SCM provider: ${config.scmm.provider}")
        }
    }

    /**
     * External host base URL, ALWAYS ending with "/".
     *
     * Source:
     *  - Uses config.scmm.url if present; otherwise builds from config.scmm.protocol + "://" + config.scmm.host.
     *
     * Notes:
     *  - This method does NOT strip paths (e.g., "/scm"). If config.scmm.url includes a path, it will be preserved;
     *    only the trailing "/" is enforced.
     *  - Intended as a base for later URI.resolve() calls.
     *
     */
    static URI externalHost(Config config) {
        def urlString = (config.scmm?.url ?: "${config.scmm.protocol}://${config.scmm.host}" as String).strip()
        def uri = URI.create(urlString)
        if (uri.toString().endsWith("/")) {
            return uri
        } else {
            return URI.create(uri.toString() + "/")
        }
    }

    /**
     * Service base URL (SCM-Manager incl. "/scm/", ALWAYS ending with "/").
     *
     * Source:
     *  - Internal: "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm/"
     *  - External: config.scmm.url (must already include the context path, e.g., "/scm")
     *
     * Notes:
     *  - Ensures a trailing "/" so java.net.URI.resolve() preserves the "/scm" segment; without it, a base may be treated as a file.
     *  - Does NOT strip paths from config.scmm.url; any provided path is preserved—only the trailing "/" is enforced.
     *  - Intended as a base for subsequent URI.resolve(...) calls.
     *  - Guarantee: always ends with "/".
     *
     * Throws:
     *  - IllegalArgumentException if external mode is selected (config.scmm.internal = false) but config.scmm.url is empty.
     */
    static URI scmmBaseUri(Config config) {
        if (config.scm.isInternal) {
            return new URI("http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm/")
        }

        def urlString = config.scm?.url?.strip() ?: ""
        if (!urlString) {
            throw new IllegalArgumentException("config.scmm.url must be set when config.scmm.internal = false")
        }
        def uri = URI.create(urlString)
        // ensure a trailing slash
        if (uri.toString().endsWith("/")) {
            return uri
        } else {
            return URI.create(uri.toString() + "/")
        }
    }

    static String scmmRepoUrl(Config config, String repoNamespaceAndName) {
        return scmmBaseUri(config).resolve("${config.scmm.rootPath}/${repoNamespaceAndName}").toString()
    }
}