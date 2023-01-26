package com.cloudogu.gitops.utils
import static org.mockito.Mockito.mock

class CommandExecutorForTest extends CommandExecutor {
    List<String> actualCommands = []
    
    @Override
    protected Output getOutput(Process proc, String command, boolean failOnError) {
        actualCommands += command
        return new Output('', '', 0)
    }

    @Override
    protected Process doExecute(String command) {
        return mock(Process)
    }
}
