package com.cloudogu.gop.cli

import io.micronaut.configuration.picocli.PicocliRunner
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(name = 'groovy-cli-graal-nativeimage-micronaut-example', description = '...',
        mixinStandardHelpOptions = true)
class GitopsPlaygroundCli implements Runnable {

    @Option(names = ['-v', '--verbose'], description = '...')
    boolean verbose

    static void main(String[] args) throws Exception {
        PicocliRunner.run(GitopsPlaygroundCli.class, args)
    }

    void run() {
        // business logic here
        if (verbose) {
            println "Hi!"
        }
    }
}
