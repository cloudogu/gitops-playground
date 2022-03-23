package com.cloudogu.gitops.cli


import groovy.util.logging.Slf4j
import io.micronaut.configuration.picocli.PicocliRunner

@Slf4j
class GitopsPlaygroundCliMain {
    
    Class<?> commandClass = GitopsPlaygroundCli.class

    static void main(String[] args) throws Exception {
        new GitopsPlaygroundCliMain().exec(args)
    }

    @SuppressWarnings('GrMethodMayBeStatic') // Non-static for easier testing
    void exec(String[] args) {
        // log levels can be set via picocli.trace sys env - defaults to 'WARN'
        if (args.contains("--trace"))
            System.setProperty("picocli.trace", "DEBUG")
        else if (args.contains("--debug"))
            System.setProperty("picocli.trace", "INFO")

        int exitCode = PicocliRunner.execute(commandClass, args)
        System.exit(exitCode)
    }
}

