package com.cloudogu.gop.cli


import groovy.cli.commons.CliBuilder

class GitopsPlaygroundCli {

    static void main(String[] args) throws Exception {
        Configuration configuration = CommandLineInterface.INSTANCE.parse(args)
    }
}

class Configuration {
    private String greet

    String getGreet() { return this.greet }

    void setGreet(String greet) { this.greet = greet }
}

/**
 * CLI-args definition and handling.
 */
enum CommandLineInterface {
    INSTANCE
    CliBuilder cliBuilder

    CommandLineInterface() {
        cliBuilder = new CliBuilder(
                usage: 'e2e [<options>]',
                header: 'Options:',
                footer: 'And here we put footer text.'
        )
        // set the amount of columns the usage message will be wide
        cliBuilder.width = 80  // default is 74
        cliBuilder.with {
            h longOpt: 'help', 'Print this help text and exit.'
        }
    }

    Configuration parse(args) {
        Configuration options = cliBuilder.parseFromInstance(Configuration.class, args)

        if (!options) {
            System.err << "Error while parsing command-line options.\n"
            System.exit 1
        }

        if (options.h) {
            cliBuilder.usage()
            System.exit 0
        }

        if (options.greet) {
            println "Hi ..."
            System.exit 0
        }

        return options
    }
}
