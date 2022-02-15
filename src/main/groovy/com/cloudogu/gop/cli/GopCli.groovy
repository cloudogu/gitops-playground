package com.cloudogu.gop.cli

import com.cloudogu.gop.GopConfig
import com.cloudogu.gop.modules.metrics.MetricsConfig
import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor

class GopCli {

    CliBuilder cliBuilder
    def cliModules = new ArrayList<CliConfig>()

    GopCli() {
        initCliBuilder()
        addCliConfigs()
        initCliArguments()
    }

    def initCliBuilder() {
        cliBuilder = new CliBuilder(
                usage: '[<options>]',
                header: 'Options:',
//                footer: 'example Footer'
        )
        cliBuilder.with {
            h longOpt: 'help', 'Print this help text and exit.'
        }
    }

    private def addCliConfigs() {
        cliModules.add(GopConfig.instance)
        cliModules.add(MetricsConfig.instance)
    }

    private def initCliArguments() {
        cliModules.forEach(config -> {
            cliBuilder.with(config.getCliArguments())
        })
    }

    def parse(String[] args) {
        OptionAccessor options = cliBuilder.parse(args)

        if (!options) {
            System.err << 'Error while parsing command-line options.\n'
            System.exit 1
        }
        if (options.h) {
            cliBuilder.usage()
            System.exit 0
        }

        cliModules.forEach(config -> {
            config.populateFields(options)
        })
    }
}
