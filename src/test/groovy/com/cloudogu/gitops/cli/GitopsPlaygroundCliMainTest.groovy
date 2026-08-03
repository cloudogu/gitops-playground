package com.cloudogu.gitops.cli

import static org.assertj.core.api.Assertions.assertThat

import uk.org.webcompere.systemstubs.SystemStubs
import org.junit.jupiter.api.Test
import picocli.CommandLine.Command
import picocli.CommandLine.Option

class GitopsPlaygroundCliMainTest {

	@Test
	void 'application returns exit code 0 on success'() {
		def gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain()
		ReturnCode returnCode = gitopsPlaygroundCliMain.exec(['--mock'] as String[], MockedCommand.class)

		assertThat(returnCode.ordinal()).isZero()
	}

	@Test
	void 'application returns exit code 1 on exception'() {
		def gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain()
		ReturnCode returnCode = gitopsPlaygroundCliMain.exec(['--mock'] as String[], ThrowingCommand.class)

		assertThat(returnCode.ordinal()).isNotZero()
	}

	@Test
	void 'application returns exit code != 0 on invalid param'() {
		int status = SystemStubs.catchSystemExit(() -> {
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