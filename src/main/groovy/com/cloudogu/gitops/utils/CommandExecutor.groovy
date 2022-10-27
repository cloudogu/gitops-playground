package com.cloudogu.gitops.utils

import groovy.util.logging.Slf4j

@Slf4j
class CommandExecutor {

    String execute(String[] command) {
        String commandString = command.join(" ")
        Process proc = command.execute()
        return getOutput(proc, commandString)
    }

    String executeAsList(String command) {
        String[] commandList = command.split(" ")
        Process proc = commandList.execute()
        return getOutput(proc, command)
    }

    String execute(String command) {
        Process proc = doExecute(command)
        return getOutput(proc, command)
    }

    protected Process doExecute(String command) {
        command.execute()
    }

    String execute(String command1, String command2) {
        Process proc = command1.execute() | command2.execute()
        String command = command1 + " | " + command2
        return getOutput(proc, command)
    }

    protected String getOutput(Process proc, String command) {
        proc.waitForOrKill(60000)
        // err must be defined first because calling proc.text closes the output stream
        String err = proc.err.text
        String out = proc.text
        if (proc.exitValue() > 0) {
            log.error("Executing command failed: ${command}")
            log.error("Stderr: ${err.toString().trim()}")
            log.error("StdOut: ${out.toString().trim()}")
            System.exit(1)
        }
        if (!out.toString().empty) {
            log.debug(command + "\n Success: -> " + out.toString().trim() + "\n")
        }
        if (!err.toString().empty) {
            log.debug(command + "\n Warning / Error -> " + err.toString().trim() + "\n")
        }
        return out.toString().trim()
    }
}
