package com.cloudogu.gop.utils

import groovy.util.logging.Slf4j

@Slf4j
class CommandExecutor {

    static def execute(List<String> command) {
        String commandString = command.join(" ")
        Process proc = command.execute()
        return getOutput(proc, commandString)
    }

    static def execute(String command) {
        Process proc = command.execute()
        return getOutput(proc, command)
    }

    static def execute(String command1, String command2) {
        Process proc = command1.execute() | command2.execute()
        String command = command1 + " | " + command2
        return getOutput(proc, command)
    }

    private static def getOutput(Process proc, String command) {
        proc.waitForOrKill(10000)
        // err must be defined first because calling proc.text closes the output stream
        String err = proc.err.text
        String out = proc.text
        if (!out.toString().empty) {
            log.debug(command + "\n\n -> " + out.toString().trim())
        }
        if (!err.toString().empty) {
            log.debug(command + "\n\n -> " + err.toString().trim())
        }
        return out.toString().trim()
    }
}
