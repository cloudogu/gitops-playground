package com.cloudogu.gitops.utils

import static org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.Test

class CommandExecutorTest {

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    
    @Test
    void aggregatesEnvironment() {
        def additionalEnv = [someKey: 'someValue']
        commandExecutor.execute('command', additionalEnv)

        assertThat(commandExecutor.actualCommands[0] as String).isEqualTo('command')
        assertThat(commandExecutor.environment.toString()).contains('someKey=someValue')
        // Make sure there are other env vars present and not solely the one we passed
        assertThat(commandExecutor.environment.size()).isGreaterThan(additionalEnv.size())
    }
}
