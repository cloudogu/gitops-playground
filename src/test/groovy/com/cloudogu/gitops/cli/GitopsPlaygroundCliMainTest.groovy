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
        int status = SystemLambda.catchSystemExit(() -> {
            gitopsPlaygroundCliMain.exec(['--mock'] as String[], MockedCommand.class)
        })

        assertThat(status).isZero()
    }

    @Test
    void 'application returns exit code 1 on exception'() {
        def gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain()
        int status = SystemLambda.catchSystemExit(() -> {
            gitopsPlaygroundCliMain.exec(['--mock'] as String[], ThrowingCommand.class)
        })

        assertThat(status).isNotZero()
    }

    @Test
    void 'application returns exit code != 0 on invalid param'() {
        int status = SystemLambda.catchSystemExit(() -> {
            GitopsPlaygroundCliMain.main(['--parameter-that-doesnt-exist ',
                                          '--debug' // avoids changing default log pattern
            ] as String[])
        })

        assertThat(status).isNotZero()
    }

    static class ThrowingCommand extends MockedCommand {
        @Override
        ReturnCode run(String[] args) {
            throw new RuntimeException("mock")
        }
    }

    @SuppressWarnings('unused')
    // Used for annotations
    static class MockedCommand extends GitopsPlaygroundCli {

        @Override
        ReturnCode run(String[] args) {
            return ReturnCode.SUCCESS
        }

        @Command
        void mockedCommand() {}

        @Option(names = ['--mock'])
        private boolean mock
    }
}
