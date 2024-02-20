package com.cloudogu.gitops.utils

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class CommandExecutor {

    public static final int PROCESS_TIMEOUT_SECONDS = 120

    Output execute(String[] command, boolean failOnError = true) {
        Process proc = doExecute(command)
        return getOutput(proc, command.join(" "), failOnError)
    }

    Output execute(String command, boolean failOnError = true) {
        Process proc = doExecute(command)
        return getOutput(proc, command, failOnError)
    }
    
    /**
     * @param envp a List of Objects (converted to Strings using toString), each member of which has environment 
     * variable settings in the format <i>name</i>=<i>value</i>, or <tt>null</tt> if the subprocess should inherit
     * the environment of the current process.
     */
    Output execute(String command, Map additionalEnv, boolean failOnError = true) {
        Map newEnv = [:] 
        newEnv.putAll(System.getenv()) // Copy existing environment variables
        newEnv.putAll(additionalEnv)
        
        Process proc = doExecute(command, newEnv.collect { key, value -> "${key}=${value}" })
        return getOutput(proc, command, failOnError)
    }

    Output execute(String command1, String command2, boolean failOnError = true) {
        Process proc = doExecute(command1) | doExecute(command2)
        String command = command1 + " | " + command2
        return getOutput(proc, command, failOnError)
    }
    
    protected Process doExecute(String command, List envp = null) {
        log.trace("Executing command: '${command}'")
        command.execute(envp, null)
    }
    
    protected Process doExecute(String[] command) {
        log.trace("Executing command: '${command}'")
        command.execute()
    }

    protected Output getOutput(Process proc, String command, boolean failOnError = true) {
        // TODO stream err and out while waiting, like this method would
        // proc.waitForProcessOutput(System.out, System.err)
        // but also use timeout. Groovy doesn't seem to offer both
        // We could write our on groovy process class that uses a timeout  self.waitFor(timeout)
        // Or Use the java process builder
        proc.waitForOrKill(PROCESS_TIMEOUT_SECONDS * 1000)
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
