package com.cloudogu.gitops.utils;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class CommandExecutorForTest extends CommandExecutor {

	@Getter
	private final List<String> actualCommands = new ArrayList<>();

	private final Queue<Output> outputs = new LinkedList<>();

	// This is actually only set when an env is passed to CommandExecutor
	@Getter
	private List<String> environment = new ArrayList<>();

	public void enqueueOutput(Output output) {
		outputs.add(output);
	}

	public void enqueueOutputs(Queue<Output> outputsQueue) {
		outputs.addAll(outputsQueue);
	}

	@Override
	protected Output getOutput(Process proc, String command, boolean failOnError) {
		actualCommands.add(command);
		Output output = outputs.poll();
		if (output == null) {
			output = new Output("", "", 0);
		}

		if (failOnError && output.getExitCode() > 0) {
			throw new RuntimeException("Executing command failed: " + command);
		}

		return output;
	}

	@Override
	protected Process doExecute(String command) {
		return mock(Process.class);
	}

	@Override
	protected Process doExecute(String[] command) {
		return mock(Process.class);
	}

	@Override
	protected Process doExecute(String command, List<String> envp) {
		environment = envp;
		return mock(Process.class);
	}

	public String assertExecuted(String commandStartsWith) {
		String actualCommand = actualCommands.stream()
											 .filter(command -> command.startsWith(commandStartsWith))
											 .findFirst()
											 .orElse(null);

		assertThat(actualCommand)
			.as(
				"Expected command to have been executed, but was not:\n%s.\nActual commands:\n%s",
				commandStartsWith,
				String.join("\n", actualCommands)
			)
			.isNotNull();
		return actualCommand;
	}

	public void assertNotExecuted(String commandStartsWith) {
		String actualCommand = actualCommands.stream()
											 .filter(command -> command.startsWith(commandStartsWith))
											 .findFirst()
											 .orElse(null);

		assertThat(actualCommand)
			.as("Expected command to have been executed, but was not: %s", commandStartsWith)
			.isNull();
	}
}
