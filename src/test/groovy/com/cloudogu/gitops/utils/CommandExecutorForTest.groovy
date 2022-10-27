package com.cloudogu.gitops.utils
import static org.mockito.Mockito.mock

class CommandExecutorForTest extends CommandExecutor {
    List<String> actualCommands = []
    
    @Override
    protected String getOutput(Process proc, String command) {
        actualCommands += command
    }

    @Override
    protected Process doExecute(String command) {
        return mock(Process)
    }
}
