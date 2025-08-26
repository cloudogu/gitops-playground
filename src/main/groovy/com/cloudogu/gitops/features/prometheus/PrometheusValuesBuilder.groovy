package com.cloudogu.gitops.features.prometheus

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmUrlResolver

class PrometheusValuesBuilder {
    private final Config config

    // Used only when OpenShift is enabled; ignored otherwise.
    private final String uid

    PrometheusValuesBuilder(Config config, String uid) {
        this.config = config
        this.uid = uid
    }

    // Template context für HELM_VALUES_PATH (prometheus-stack-helm-values.ftl.yaml etc.)
    Map<String, Object> build() {
        def model = [
                monitoring: [grafana: [host: hostOf(config.features?.monitoring?.grafanaUrl)]],
                namespaces: (config.application.namespaces.activeNamespaces ?: []) as LinkedHashSet<String>,
                scmm      : scmConfigurationMetrics(),
                jenkins   : jenkinsConfigurationMetrics(),
                uid       : uid,
                images    : [
                        prometheus    : imageOrNull(config.features.monitoring.helm?.prometheusImage),
                        operator      : imageOrNull(config.features.monitoring.helm?.prometheusOperatorImage),
                        reloader      : imageOrNull(config.features.monitoring.helm?.prometheusConfigReloaderImage),
                        grafana       : imageOrNull(config.features.monitoring.helm?.grafanaImage),
                        grafanaSideCar: imageOrNull(config.features.monitoring.helm?.grafanaSidecarImage)
                ],
                config    : config
        ] as Map<String, Object>

        return model
    }

    private Map scmConfigurationMetrics() {
        def uri = ScmUrlResolver.baseUri(config).resolve("api/v2/metrics/prometheus")
        [
                protocol: uri.scheme ?: "",
                host    : uri.authority ?: "",
                path    : uri.path ?: ""
        ]
    }

    private Map jenkinsConfigurationMetrics() {
        def uri = baseUriJenkins(config).resolve("prometheus")
        [
                metricsUsername: config.jenkins.metricsUsername ?: "",
                protocol       : uri.scheme ?: "",
                host           : uri.authority ?: "",
                path           : uri.path ?: ""
        ]
    }

    private static URI baseUriJenkins(Config config) {
        if (config.jenkins.internal) {
            return new URI("http://jenkins.${config.application.namePrefix}jenkins.svc.cluster.local/")
        }
        def urlString = config.jenkins?.url?.strip() ?: ""
        if (!urlString) {
            throw new IllegalArgumentException("config.jenkins.url must be set when config.jenkins.internal = false")
        }
        def url = URI.create(urlString)
        return url.toString().endsWith("/") ? url : URI.create(url.toString() + "/")
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
