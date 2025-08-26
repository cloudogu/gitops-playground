package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo

class ArgoCDTemplateContextBuilder {
    private final Config config

    ArgoCDTemplateContextBuilder(Config config) {
        this.config = config
    }

    Map<String, Object> build() {
        def model = [
                tenantName: tenantName(config.application.namePrefix),
                nginxImage: imageOrNull(config?.images?.nginx),
                argocd    : [host: hostOf(config.features?.argocd?.url)],
                scmm      : [
                        baseUrl       : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm" : ScmmRepo.createScmmUrl(config),
                        host          : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local" : config.scmm.host,
                        protocol      : config.scmm.internal ? 'http' : config.scmm.protocol,
                        repoUrl       : ScmmRepo.createSCMBaseUrl(config),
                        centralScmmUrl: !config.multiTenant.internal ? config.multiTenant.centralScmUrl : "http://scmm.scm-manager.svc.cluster.local/scm"
                ],
                config    : config
        ] as Map<String, Object>

        return model
    }

    private static String tenantName(String namePrefix) {
        if (!namePrefix) return ""
        return namePrefix.replaceAll(/-$/, "")
    }

    static String hostOf(String url) {
        if (!url) return ""
        try {
            return new URL(url).host ?: ""
        }
        catch (Exception ignored) {
            return ""
        }
    }

    private static Object imageOrNull(String imageRef) {
        def ref = (imageRef ?: "").strip()
        if (!ref) return null
        return com.cloudogu.gitops.utils.DockerImageParser.parse(ref)
    }
}
