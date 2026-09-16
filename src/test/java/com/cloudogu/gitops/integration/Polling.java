package com.cloudogu.gitops.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

public final class Polling {

	private Polling() {
	}

	public static void until(BooleanSupplier condition, Duration timeout, Duration pollInterval) {
		untilAsserted(() -> {
			if (!condition.getAsBoolean()) {
				throw new AssertionError("Condition is not fulfilled");
			}
		}, timeout, pollInterval);
	}

	public static void untilAsserted(Runnable assertion, Duration timeout, Duration pollInterval) {
		if (timeout.isNegative()) {
			throw new IllegalArgumentException("Timeout must not be negative");
		}
		if (pollInterval.isNegative()) {
			throw new IllegalArgumentException("Poll interval must not be negative");
		}

		Instant deadline = Instant.now().plus(timeout);
		Throwable lastFailure;
		do {
			try {
				assertion.run();
				return;
			} catch (RuntimeException | AssertionError failure) {
				lastFailure = failure;
			}

			sleepUntilNextAttempt(pollInterval, deadline);
		} while (Instant.now().isBefore(deadline));

		throw new TimeoutException(timeout, lastFailure);
	}

	private static void sleepUntilNextAttempt(Duration pollInterval, Instant deadline) {
		long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
		if (remainingMillis <= 0) {
			return;
		}

		long sleepMillis = Math.min(pollInterval.toMillis(), remainingMillis);
		try {
			Thread.sleep(sleepMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for condition", e);
		}
	}

	public static final class TimeoutException extends RuntimeException {

		private TimeoutException(Duration timeout, Throwable cause) {
			super("Condition was not fulfilled within " + timeout, cause);
		}
	}
}
