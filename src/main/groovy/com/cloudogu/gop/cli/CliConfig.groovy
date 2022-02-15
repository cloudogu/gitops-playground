package com.cloudogu.gop.cli


import groovy.cli.picocli.OptionAccessor

interface CliConfig {

    Closure getCliArguments()

    def populateFields(OptionAccessor options)
}