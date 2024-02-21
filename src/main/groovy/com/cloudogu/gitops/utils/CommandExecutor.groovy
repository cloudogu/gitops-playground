package com.cloudogu.gitops.utils

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton
import org.apache.commons.io.output.TeeOutputStream

import java.util.concurrent.TimeUnit

@Slf4j
@Singleton
class CommandExecutor {

    /* This timeout is mainly here to not freeze forever the apply process in the worst case scenario.
    
       Calls to init-scmm.sh and init-jenkins.sh take several minutes at best and might be slower with poor connections 
       to the internet.
       Once they are migrated to groovy we can reduce this timeout.*/
    public static final int PROCESS_TIMEOUT_MINUTES = 15

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
        TeeOutputStream teeOut, teeErr
        
        if (log.isTraceEnabled()) {
            // While waiting for the process to finish, also print stdout and stderr streams through to the main process
            teeOut = new TeeOutputStream(stdOut, System.out)
            teeErr = new TeeOutputStream(stdErr, System.err)
            proc.consumeProcessOutput(teeOut, teeErr)
        } else {
            proc.consumeProcessOutput(stdOut, stdErr)
        }

        def processFinished = proc.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!processFinished) {
            log.error("Timeout waiting for command ${command}. Killing process.")
            proc.waitForOrKill(1)
        }

        // Make sure all bytes have been written, before returning output
        if (teeOut)  teeOut.flush()
        if (teeErr) teeErr.flush()
        
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
