package com.cloudogu.gop.modules.metrics

import com.cloudogu.gop.cli.CliConfig
import com.cloudogu.gop.cli.GopCli
import groovy.cli.picocli.OptionAccessor

class MetricsConfig implements CliConfig{

    private static final INSTANCE = new MetricsConfig()

    GopCli gopCli

    boolean metrics

    private MetricsConfig() {}

    static def getInstance() {
        return INSTANCE
    }

    @Override
    Closure getCliArguments() {
        return {
            _(longOpt: 'metrics', 'Installs the Kube-Prometheus-Stack for ArgoCD. This includes Prometheus, the Prometheus operator, Grafana and some extra resources')
        }
    }

    @Override
    def populateFields(OptionAccessor options) {
        if (options.metrics) {
            metrics = options.metrics
        }
    }

    private def setMetrics(boolean metrics) {}
}
