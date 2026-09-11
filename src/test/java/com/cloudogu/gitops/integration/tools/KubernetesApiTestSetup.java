package com.cloudogu.gitops.integration.tools;

import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.fail;

public abstract class KubernetesApiTestSetup {

	int TIME_TO_WAIT = 12;
	int RETRY_SECONDS = 30;

	/**
	 * Waits until the Kubernetes resources required by the integration test are ready.
	 */
	@BeforeEach
	void waitUntilReady() {
		waitForCondition(
			this::waitingCondition,
			maxWaitTimeInMinutes(TIME_TO_WAIT),
			pollIntervallSeconds(RETRY_SECONDS)
		);
	}

	static void waitForCondition(Supplier<Boolean> condition, Duration timeout, Duration pollInterval) {
		Instant end = Instant.now().plus(timeout);
		while (Instant.now().isBefore(end)) {
			if (condition.get()) {
				return;
			}
			try {
				Thread.sleep(pollInterval.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("break polling", e);
			}
		}
		fail("Wait condition not fulfilled in time");
	}

	private Duration pollIntervallSeconds(int time) {
		return Duration.ofSeconds(time);
	}

	private Duration maxWaitTimeInMinutes(int time) {
		return Duration.ofMinutes(time);
	}

	boolean waitingCondition() {
		System.out.println("waiting for pods");
		return isReadyToStartTests();
	}

	/**
	 * This condition is to override, if test has to wait, i.e. ArgoCD has to do its GitOps magic.
	 *
	 * @return whether the tests are ready to start
	 */
	abstract boolean isReadyToStartTests();
}
