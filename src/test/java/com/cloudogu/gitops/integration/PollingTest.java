package com.cloudogu.gitops.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingTest {

	@Test
	void retriesUntilAssertionSucceeds() {
		AtomicInteger attempts = new AtomicInteger();

		Polling.untilAsserted(
			() -> assertThat(attempts.incrementAndGet()).isGreaterThanOrEqualTo(3),
			Duration.ofSeconds(1),
			Duration.ZERO
		);

		assertThat(attempts).hasValue(3);
	}

	@Test
	void reportsLastFailureOnTimeout() {
		assertThatThrownBy(() -> Polling.untilAsserted(
			() -> {
				throw new AssertionError("not ready");
			},
			Duration.ZERO,
			Duration.ZERO
		))
			.isInstanceOf(Polling.TimeoutException.class)
			.hasCauseInstanceOf(AssertionError.class)
			.hasRootCauseMessage("not ready");
	}

	@Test
	void restoresInterruptStatus() {
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> Polling.until(
				() -> false,
				Duration.ofSeconds(1),
				Duration.ofMillis(1)
			))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Interrupted while waiting for condition")
				.hasCauseInstanceOf(InterruptedException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}
}
