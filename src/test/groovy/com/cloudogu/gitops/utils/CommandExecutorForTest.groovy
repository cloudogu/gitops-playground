package com.cloudogu.gitops.utils

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock

class CommandExecutorForTest extends CommandExecutor {
    List<String> actualCommands = []

    Queue<Output> outputs = new LinkedList<Output>()

    void enqueueOutput(Output output) {
        outputs.add(output)
    }
    
    @Override
    protected Output getOutput(Process proc, String command, boolean failOnError) {
        actualCommands += command

        return outputs.poll() ?: new Output('', '', 0)
    }

    @Override
    protected Process doExecute(String command) {
        return mock(Process)
    }

    @Override
    protected Process doExecute(String[] command) {
        return mock(Process)
    }

    String assertExecuted(String commandStartsWith) {
        def actualCommand = actualCommands.find {
            it.startsWith(commandStartsWith)
        }
        assertThat(actualCommand).as("Expected command to have been executed, but was not: ${commandStartsWith}")
                .isNotNull()
        return actualCommand
    }

    void assertNotExecuted(String commandStartsWith) {
        def actualCommand = actualCommands.find {
            it.startsWith(commandStartsWith)
        }
        assertThat(actualCommand).as("Expected command to have been executed, but was not: ${commandStartsWith}")
                .isNull()
    }
}
