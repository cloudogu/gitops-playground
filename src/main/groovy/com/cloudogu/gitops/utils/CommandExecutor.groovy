package com.cloudogu.gitops.utils

import groovy.util.logging.Slf4j

@Slf4j
class CommandExecutor {

    Output execute(String[] command, boolean failOnError = true) {
        Process proc = doExecute(command)
        return getOutput(proc, command.join(" "), failOnError)
    }

    Output execute(String command, boolean failOnError = true) {
        Process proc = doExecute(command)
        return getOutput(proc, command, failOnError)
    }

    Output execute(String command1, String command2, boolean failOnError = true) {
        Process proc = doExecute(command1) | doExecute(command2)
        String command = command1 + " | " + command2
        return getOutput(proc, command, failOnError)
    }
    
    protected Process doExecute(String command) {
        log.trace("Executing command: '${command}'")
        command.execute()
    }
    
    protected Process doExecute(String[] command) {
        log.trace("Executing command: '${command}'")
        command.execute()
    }

    protected Output getOutput(Process proc, String command, boolean failOnError = true) {
        proc.waitForOrKill(60000)
        // err must be defined first because calling proc.text closes the output stream
        String err = proc.err.text.trim()
        String out = proc.text.trim()
        if (failOnError && proc.exitValue() > 0) {
            log.error("Executing command failed: ${command}")
            log.error("Stderr: ${err.toString().trim()}")
            log.error("StdOut: ${out.toString().trim()}")
            System.exit(1)
        }
        if (out) {
            log.debug("${command}\n Success: ${out}")
        }
        if (err) {
            log.debug("${command}\n Warning / Error: ${err}")
        }
        return new Output(err, out, proc.exitValue())
    }
    
    static class Output {
        String stdErr
        String stdOut
        int exitCode
        
        Output(String stdErr, String stdOut, int exitCode) {
            this.stdErr = stdErr
            this.stdOut = stdOut
            this.exitCode = exitCode
        }
    }
}
