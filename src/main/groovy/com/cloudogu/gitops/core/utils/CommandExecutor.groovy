package com.cloudogu.gitops.core.utils

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
        Process proc = command.execute()
        return getOutput(proc, command)
    }

    String execute(String command1, String command2) {
        Process proc = command1.execute() | command2.execute()
        String command = command1 + " | " + command2
        return getOutput(proc, command)
    }

    private String getOutput(Process proc, String command) {
        proc.waitForOrKill(10000)
        // err must be defined first because calling proc.text closes the output stream
        String err = proc.err.text
        String out = proc.text
        if (!out.toString().empty) {
            log.debug(command + "\n Success: -> " + out.toString().trim() + "\n")
        }
        if (!err.toString().empty) {
            log.debug(command + "\n Warning / Error -> " + err.toString().trim() + "\n")
        }
        return out.toString().trim()
    }
}
