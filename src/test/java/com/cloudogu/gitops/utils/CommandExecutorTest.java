package com.cloudogu.gitops.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorTest {

	private final CommandExecutorForTest commandExecutor = new CommandExecutorForTest();

	@Test
	void aggregatesEnvironment() {
		Map<String, Object> additionalEnv = Map.of("someKey", "someValue");
		commandExecutor.execute("command", additionalEnv);

		assertThat(commandExecutor.getActualCommands().get(0)).isEqualTo("command");
		assertThat(commandExecutor.getEnvironment().toString()).contains("someKey=someValue");
		// Make sure there are other env vars present and not solely the one we passed
		assertThat(commandExecutor.getEnvironment().size()).isGreaterThan(additionalEnv.size());
	}
}
