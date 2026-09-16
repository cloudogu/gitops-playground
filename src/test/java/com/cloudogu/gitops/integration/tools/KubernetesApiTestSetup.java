package com.cloudogu.gitops.integration.tools;

import com.cloudogu.gitops.integration.Polling;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;

public abstract class KubernetesApiTestSetup {

	int TIME_TO_WAIT = 12;
	int RETRY_SECONDS = 30;

	/**
	 * Waits until the Kubernetes resources required by the integration test are ready.
	 */
	@BeforeEach
	void waitUntilReady() {
		Polling.until(
			this::waitingCondition,
			maxWaitTimeInMinutes(TIME_TO_WAIT),
			pollIntervallSeconds(RETRY_SECONDS)
		);
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
