package com.cloudogu.gitops.utils

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton
import org.apache.commons.io.output.TeeOutputStream

import java.util.concurrent.TimeUnit

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
        ByteArrayOutputStream stdOut = new ByteArrayOutputStream()
        ByteArrayOutputStream stdErr = new ByteArrayOutputStream()
        
        if (log.isTraceEnabled()) {
            // Pass stdout and stderr streams through to the main process while waiting
            TeeOutputStream teeOut = new TeeOutputStream(System.out, stdOut)
            TeeOutputStream teeErr = new TeeOutputStream(System.err, stdErr)
            proc.consumeProcessOutput(teeOut, teeErr)
        } else {
            proc.consumeProcessOutput(stdOut, stdErr)
        }

        def processFinished = proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!processFinished) {
            log.error("Timeout waiting for command ${command}. Killing process.")
            proc.waitForOrKill(1)
        }
        
        if (failOnError && proc.exitValue() > 0) {
            log.error("Executing command failed: ${command}")
            System.exit(1)
        }

        return new Output(stdErr.toString().trim(), stdOut.toString().trim(), proc.exitValue())
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
