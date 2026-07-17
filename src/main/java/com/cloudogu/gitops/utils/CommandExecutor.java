package com.cloudogu.gitops.utils;

import jakarta.inject.Singleton;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
@SuppressWarnings({"rawtypes", "unchecked"})
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    /* This timeout is mainly here to not freeze forever the apply process in the worst case scenario.

       Calls to init-scmm.sh and init-jenkins.sh take several minutes at best and might be slower with poor connections
       to the internet.
       Once they are migrated to groovy we can reduce this timeout.*/
    public static final int PROCESS_TIMEOUT_MINUTES = 15;

    public Output execute(String[] command) {
        return execute(command, true);
    }

    public Output execute(String[] command, boolean failOnError) {
        try {
            Process proc = doExecute(command);
            return getOutput(proc, String.join(" ", command), failOnError);
        } catch (IOException e) {
            throw new RuntimeException("Failed to execute command: " + String.join(" ", command), e);
        }
    }

    /**
     * Please prefer using {@link #execute(java.lang.String[], boolean)}, because
     * it avoids quoting issues when passing arguments containing whitespaces.
     */
    @Deprecated
    public Output execute(String command) {
        return execute(command, true);
    }

    @Deprecated
    public Output execute(String command, boolean failOnError) {
        try {
            Process proc = doExecute(command);
            return getOutput(proc, command, failOnError);
        } catch (IOException e) {
            throw new RuntimeException("Failed to execute command: " + command, e);
        }
    }

    public Output execute(String command, Map additionalEnv) {
        return execute(command, additionalEnv, true);
    }

    /**
     * @param additionalEnv a Map of env variables to add
     */
    public Output execute(String command, Map additionalEnv, boolean failOnError) {
        try {
            java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
            if (additionalEnv != null) {
                additionalEnv.forEach((key, value) -> {
                    env.put(String.valueOf(key), value != null ? String.valueOf(value) : null);
                });
            }
            List<String> envp = env.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + (entry.getValue() != null ? entry.getValue() : ""))
                    .toList();

            Process proc = doExecute(command, envp);
            return getOutput(proc, command, failOnError);
        } catch (IOException e) {
            throw new RuntimeException("Failed to execute command: " + command, e);
        }
    }

    public Output execute(String[] command1, String[] command2) {
        return execute(command1, command2, true);
    }

    public Output execute(String[] command1, String[] command2, boolean failOnError) {
        String pipedCommand = String.join(" ", command1) + " | " + String.join(" ", command2);
        try {
            ProcessBuilder pb1 = new ProcessBuilder(command1);
            ProcessBuilder pb2 = new ProcessBuilder(command2);
            List<Process> processes = ProcessBuilder.startPipeline(List.of(pb1, pb2));
            Process process1 = processes.get(0);
            Process process2 = processes.get(1);

            Output finalOutput = getOutput(process2, pipedCommand, false);
            // Proc1 should have finished when proc2 has.
            // Still, there is the occasional "IllegalThreadStateException: process hasn't exited"... concurrency 🤷
            // Avoid the exceptions, by explicitly waiting for the process to end
            waitForOrKill(process1, String.join(" ", command1));

            if (process1.exitValue() > 0) {
                log.error("Pipefail! First process of command failed " + pipedCommand + ".");
                try (var is = process1.getErrorStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    is.transferTo(bos);
                    log.error("Stderr: " + bos.toString().trim());
                } catch (IOException ignored) {}
            }
            if (process2.exitValue() > 0) {
                log.error("Executing command failed: " + pipedCommand);
                log.error("Stderr: " + finalOutput.getStdErr());
                log.error("StdOut: " + finalOutput.getStdOut());
            }

            boolean success = process1.exitValue() == 0 && process2.exitValue() == 0;
            if (!success && failOnError) {
                throw new RuntimeException("Executing command failed: " + pipedCommand);
            }

            return finalOutput;
        } catch (IOException e) {
            throw new RuntimeException("Failed to execute piped command: " + pipedCommand, e);
        }
    }

    protected Process doExecute(String command, List<String> envp) throws IOException {
        log.trace("Executing command: '{}'", command);
        String[] envpArray = envp != null ? envp.toArray(new String[0]) : null;
        return Runtime.getRuntime().exec(command, envpArray);
    }

    protected Process doExecute(String command) throws IOException {
        return doExecute(command, (List<String>) null);
    }

    protected Process doExecute(String[] command) throws IOException {
        log.trace("Executing command: '{}'", (Object) command);
        return Runtime.getRuntime().exec(command);
    }

    protected Output getOutput(Process proc, String command, boolean failOnError) {
        ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
        ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
        OutputStream outDest = stdOut;
        OutputStream errDest = stdErr;

        TeeOutputStream teeOut = null;
        TeeOutputStream teeErr = null;

        if (log.isTraceEnabled()) {
            // While waiting for the process to finish, also print stdout and stderr streams through to the main process
            teeOut = new TeeOutputStream(stdOut, System.out);
            teeErr = new TeeOutputStream(stdErr, System.err);
            outDest = teeOut;
            errDest = teeErr;
        }

        final OutputStream finalOutDest = outDest;
        final OutputStream finalErrDest = errDest;

        Thread outThread = new Thread(() -> {
            try (var is = proc.getInputStream()) {
                is.transferTo(finalOutDest);
            } catch (IOException ignored) {}
        });
        Thread errThread = new Thread(() -> {
            try (var es = proc.getErrorStream()) {
                es.transferTo(finalErrDest);
            } catch (IOException ignored) {}
        });

        outThread.start();
        errThread.start();

        waitForOrKill(proc, command);

        try {
            outThread.join();
            errThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Make sure all bytes have been written, before returning output
        if (teeOut != null) {
            try {
                teeOut.flush();
            } catch (IOException ignored) {}
        }
        if (teeErr != null) {
            try {
                teeErr.flush();
            } catch (IOException ignored) {}
        }

        Output output = new Output(stdErr.toString().trim(), stdOut.toString().trim(), proc.exitValue());

        if (failOnError && proc.exitValue() > 0) {
            log.error("Executing command failed: " + command);
            log.error("Stderr: " + output.getStdErr());
            log.error("StdOut: " + output.getStdOut());
            throw new RuntimeException("Executing command failed: " + command);
        }

        return output;
    }

    protected void waitForOrKill(Process proc, String command) {
        try {
            boolean processFinished = proc.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!processFinished) {
                log.error("Timeout waiting for command " + command + ". Killing process.");
                proc.destroyForcibly();
                proc.waitFor();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for command " + command + ". Killing process.", e);
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public static class Output {
        private final String stdErr;
        private final String stdOut;
        private final int exitCode;

        public Output(String stdErr, String stdOut, int exitCode) {
            this.stdErr = stdErr;
            this.stdOut = stdOut;
            this.exitCode = exitCode;
        }

        public String getStdErr() {
            return stdErr;
        }

        public String getStdOut() {
            return stdOut;
        }

        public int getExitCode() {
            return exitCode;
        }
    }
}
