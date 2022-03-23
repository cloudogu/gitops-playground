package com.cloudogu.gitops.cli


import com.github.stefanbirkner.systemlambda.SystemLambda
import org.junit.jupiter.api.Test
import picocli.CommandLine.Command
import picocli.CommandLine.Option

import static org.assertj.core.api.Assertions.assertThat 

class GitopsPlaygroundCliMainTest {

    @Test
    void 'application returns exit code 0 on success'() {
        def gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain()
        gitopsPlaygroundCliMain.commandClass = MockedCommand.class
        int status = SystemLambda.catchSystemExit(() -> {
            gitopsPlaygroundCliMain.exec(['--mock'] as String[])
        })

        assertThat(status).isZero()
    }

    @Test
    void 'application returns exit code 1 on exception'() {
        def gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain()
        gitopsPlaygroundCliMain.commandClass = ThrowingCommand.class
        int status = SystemLambda.catchSystemExit(() -> {
            gitopsPlaygroundCliMain.exec(['--mock'] as String[])
        })

        assertThat(status).isNotZero()
    }
    
    @Test
    void 'application returns exit code != 0 on invalid param'() {
        int status = SystemLambda.catchSystemExit(() -> {
            GitopsPlaygroundCliMain.main(['--parameter-that-doesnt-exist'] as String[])
        })

        assertThat(status).isNotZero()
    }
    
    static class ThrowingCommand extends MockedCommand {
        @Override
        void run() {
            throw new RuntimeException("mock")
        }
    }

    @SuppressWarnings('unused') // Used for annotations
    static class MockedCommand implements Runnable {
        
        @Override
        void run() {
        }

        @Command
        void mockedCommand() {}
        
        @Option(names = ['--mock'])
        private boolean mock
    }
}