package com.cloudogu.gitops.cli


import groovy.util.logging.Slf4j
import picocli.CommandLine

@Slf4j
class GitopsPlaygroundCliMain {
    
    Class<?> commandClass = GitopsPlaygroundCli.class

    static void main(String[] args) throws Exception {
        new GitopsPlaygroundCliMain().exec(args)
    }

    @SuppressWarnings('GrMethodMayBeStatic') // Non-static for easier testing
    void exec(String[] args) {
        // log levels can be set via picocli.trace sys env - defaults to 'WARN'
        if (args.contains('--trace') || args.contains('-x'))
            System.setProperty("picocli.trace", "DEBUG")
        else if (args.contains('--debug') || args.contains('-d'))
            System.setProperty("picocli.trace", "INFO")

        def app = commandClass.getDeclaredConstructor().newInstance()
        def exitCode = new CommandLine(app)
                .execute(args)

        System.exit(exitCode)
    }
}
