package com.cloudogu.gop

import com.cloudogu.gop.cli.CliConfig
import com.cloudogu.gop.cli.GopCli
import groovy.cli.picocli.OptionAccessor

class GopConfig implements CliConfig {

    private static final INSTANCE = new GopConfig()

    GopCli gopCli

    String username
    String password
    boolean yes
    boolean debug
    boolean trace
    boolean remote
    boolean insecure

    private GopConfig() {}

    static def getInstance() {
        return INSTANCE
    }

    @Override
    Closure getCliArguments() {
        return  {
            _(longOpt: 'username', args: 1, argName: 'String', 'Set initial admin username')
            _(longOpt: 'password', args: 1, argName: 'String', 'Set initial admin password')
            y(longOpt: 'yes', 'Skip kubecontext confirmation')
            d(longOpt: 'debug', 'Debug output')
            x(longOpt: 'trace', 'Debug + show each command executet (set -x)')
            _(longOpt: 'remote', 'Install on remote cluster e.g. gcp')
            _(longOpt: 'insecure', 'Sets insecure-mode in cURL which skips cert validation')
        }
    }

    @Override
    def populateFields(OptionAccessor options) {
        if (options.username) {
            username = options.username
        }
        if (options.password) {
            password = options.password
        }
        if (options.yes) {
            yes = options.yes
        }
        if (options.debug) {
            debug = options.debug
        }
        if (options.trace) {
            trace = options.trace
        }
        if (options.remote) {
            remote = options.remote
        }
        if (options.insecure) {
            insecure = options.insecure
        }
    }

    private def setGopCli(GopCli gopCli) {}

    private def setUsername(String username) {}

    private def setPassword(String password) {}

    private def setYes(boolean yes) {}

    private def setDebug(boolean debug) {}

    private def setTrace(boolean trace) {}

    private def setRemote(boolean remote) {}

    private def setInsecure(boolean insecure) {}
}
