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

    /**
     * Please prefer using {@link #execute(java.lang.String[], boolean)}, because 
     * it avoids quoting issues when passing arguments containing whitespaces.
     */
    @Deprecated
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

    Output execute(String[] command1, String[] command2, boolean failOnError = true) {
        String pipedCommand = "${command1.join(' ')} | ${command2.join(' ')}"
        def process1 = doExecute(command1)
        def process2 = doExecute(command2)
        
        def finalOutput = getOutput(process1.pipeTo(process2), pipedCommand, false)
        // Proc1 should have finished when proc2 has. 
        // Still, there is the occasional "IllegalThreadStateException: process hasn't exited"... concurrency 🤷
        // Avoid the exceptions, by explicitly waiting for the process  to end
        waitForOrKill(process1, command1.join(' '))
        
        if (process1.exitValue() > 0) {
            log.error("Pipefail! First process of command failed ${pipedCommand}.")
            log.error("Stderr: ${process1.err.text.trim()}")
        }
        if (process2.exitValue() > 0) {
            log.error("Executing command failed: ${pipedCommand}")
            log.error("Stderr: ${finalOutput.stdErr}")
            log.error("StdOut: ${finalOutput.stdOut}")
        }
        
        boolean success = process1.exitValue() == 0 && process2.exitValue() == 0
        if (!success && failOnError) {
            throw new RuntimeException("Executing command failed: ${pipedCommand}")
        }
        
        return finalOutput
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

        waitForOrKill(proc, command)

        // Make sure all bytes have been written, before returning output
        if (teeOut) teeOut.flush()
        if (teeErr) teeErr.flush()
        def output = new Output(stdErr.toString().trim(), stdOut.toString().trim(), proc.exitValue())
        
        if (failOnError && proc.exitValue() > 0) {
            log.error("Executing command failed: ${command}")
            log.error("Stderr: ${output.stdErr}")
            log.error("StdOut: ${output.stdOut}")
            if (failOnError) {
                throw new RuntimeException("Executing command failed: ${command}")
            }
        }

        return output
    }

    protected void waitForOrKill(Process proc, String command) {
        def processFinished = proc.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!processFinished) {
            log.error("Timeout waiting for command ${command}. Killing process.")
            proc.waitForOrKill(1)
        }
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
