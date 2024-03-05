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

    /**
     * Please prefer using {@link #execute(java.lang.String[], boolean)}, because 
     * it avoids quoting issues when passing arguments containing whitespaces.
     */
    @Deprecated
    Output execute(String command, boolean failOnError = true) {
        Process proc = doExecute(command)
        return getOutput(proc, command, failOnError)
    }

    Output execute(String[] command1, String[] command2, boolean failOnError = true) {
        String command = "${command1.join(' ')} | ${command2.join(' ')}"
        def process1 = doExecute(command1)
        def process2 = doExecute(command2)
        
        def finalOutput = getOutput(process1.pipeTo(process2), command, false)
        
        if (process1.exitValue() > 0) {
            log.error("Pipefail! First process of command failed ${command}.")
            log.error("Stderr: ${process1.err.text.trim()}")
        }
        if (process2.exitValue() > 0) {
            log.error("Executing command failed: ${command}")
            log.error("Stderr: ${finalOutput.stdErr}")
            log.error("StdOut: ${finalOutput.stdOut}")
        }
        
        boolean success = process1.exitValue() == 0 && process2.exitValue() == 0
        if (!success && failOnError) {
            throw new RuntimeException("Executing command failed: ${command}")
        }
        
        return finalOutput
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
        proc.waitForOrKill(PROCESS_TIMEOUT_SECONDS * 1000)
        // err must be defined first because calling proc.text closes the output stream
        String err = proc.err.text.trim()
        String out = proc.text.trim()
        if (proc.exitValue() > 0) {
            log.error("Executing command failed: ${command}")
            log.error("Stderr: ${err.toString().trim()}")
            log.error("StdOut: ${out.toString().trim()}")
            if (failOnError) {
                throw new RuntimeException("Executing command failed: ${command}")
            }
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
