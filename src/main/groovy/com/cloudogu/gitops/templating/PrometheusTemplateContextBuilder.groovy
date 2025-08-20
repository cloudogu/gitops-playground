package com.cloudogu.gitops.templating

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmUrlResolver
import com.cloudogu.gitops.utils.DockerImageParser

class PrometheusTemplateContextBuilder {
    private final Config config

    // Used only when OpenShift is enabled; ignored otherwise.
    private final Closure<String> uidProvider

    PrometheusTemplateContextBuilder(Config config, Closure<String> uidProvider = { "" }) {
        this.config = config
        this.uidProvider = uidProvider
    }

    // Template context für HELM_VALUES_PATH (prometheus-stack-helm-values.ftl.yaml etc.)
    Map<String, Object> valuesContext() {
        def model = [
                monitoring: [ grafana: [ host: hostOf(config.features?.monitoring?.grafanaUrl) ] ],
                namespaces: (config.application.namespaces.activeNamespaces ?: []) as LinkedHashSet<String>,
                scmm     : (scmConfigurationMetrics() ?: [:]),
                jenkins  : (jenkinsConfigurationMetrics() ?: [:]),
                uid      : (config.application?.openshift ? safeUid() : ""),
                images : [
                        prometheus : imageMapOrNull(config.features.monitoring.helm?.prometheusImage),
                        operator   : imageMapOrNull(config.features.monitoring.helm?.prometheusOperatorImage),
                        reloader   : imageMapOrNull(config.features.monitoring.helm?.prometheusConfigReloaderImage),
                        grafana    : imageMapOrNull(config.features.monitoring.helm?.grafanaImage),
                        grafanaSideCar : imageMapOrNull(config.features.monitoring.helm?.grafanaSidecarImage)
                ],
                config   : config
        ] as Map<String, Object>

        return model
    }

    private String safeUid() {
        try {
            uidProvider.call() ?: ""
        } catch (ignored) {
            return ""
        }
    }

    private Map scmConfigurationMetrics() {
        def uri = ScmUrlResolver.metricsUri(config)
        [
                protocol: uri.scheme    ?: "",
                host    : uri.authority ?: "",
                path    : uri.path      ?: ""
        ]
    }

    private Map jenkinsConfigurationMetrics() {
        String path = 'prometheus'
        String base = config.jenkins.internal
                ? "http://jenkins.${config.application.namePrefix}jenkins.svc.cluster.local/${path}"
                : "${config.jenkins.url}/${path}"
        def uri = new URI(base)
        [
                metricsUsername: config.jenkins.metricsUsername ?: "",
                protocol       : uri.scheme    ?: "",
                host           : uri.authority ?: "",
                path           : uri.path      ?: ""
        ]
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

    private static Map imageMapOrNull(String imageRef) {
        def ref = (imageRef ?: "").trim()
        if (!ref) return null
        def img = DockerImageParser.parse(ref)
        [registry: img.registry, repository: img.repository, tag: img.tag]
    }
}
