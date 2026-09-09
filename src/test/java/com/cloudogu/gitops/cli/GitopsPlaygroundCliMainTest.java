package com.cloudogu.gitops.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static org.assertj.core.api.Assertions.assertThat;

class GitopsPlaygroundCliMainTest {

	@Test
	void applicationReturnsExitCodeZeroOnSuccess() {
		GitopsPlaygroundCliMain gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain();
		ReturnCode returnCode = gitopsPlaygroundCliMain.exec(new String[]{"--mock"}, MockedCommand.class);

		assertThat(returnCode.ordinal()).isZero();
	}

	@Test
	void applicationReturnsNonZeroExitCodeOnException() {
		GitopsPlaygroundCliMain gitopsPlaygroundCliMain = new GitopsPlaygroundCliMain();
		ReturnCode returnCode = gitopsPlaygroundCliMain.exec(new String[]{"--mock"}, ThrowingCommand.class);

		assertThat(returnCode.ordinal()).isNotZero();
	}

	@Test
	void applicationReturnsNonZeroExitCodeOnInvalidParam() {
		ReturnCode returnCode = new GitopsPlaygroundCliMain().exec(
			new String[]{"--parameter-that-doesnt-exist ", "--debug"},
			GitopsPlaygroundCli.class
		);

		assertThat(returnCode.ordinal()).isNotZero();
	}

	static class ThrowingCommand extends MockedCommand {
		@Override
		public ReturnCode run(String[] args) {
			throw new RuntimeException("mock");
		}
	}

	@SuppressWarnings("unused")
	static class MockedCommand extends GitopsPlaygroundCli {

		@Override
		public ReturnCode run(String[] args) {
			return ReturnCode.SUCCESS;
		}

		@Command
		void mockedCommand() {
		}

		@Option(names = "--mock")
		private boolean mock;
	}
}
