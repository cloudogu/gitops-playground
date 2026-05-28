package com.cloudogu.gitops.integration

import static org.assertj.core.api.Assertions.fail

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import groovy.util.logging.Slf4j

import io.fabric8.kubernetes.api.model.ContainerStatus
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.fabric8.kubernetes.client.dsl.ExecWatch
import org.awaitility.Awaitility

/**
 * This class contains helper methods for k8s communication.*/
@Slf4j
class TestK8sHelper {

	static final int DEFAULT_WAIT_MINUTES = 5
	static final int DEFAULT_POLL_SECONDS = 5
	static final String RUNNING = "Running"
	static final String FAILED = "Failed"
	static final String SUCCEEDED = "Succeeded"
	static final Set<String> FATAL_CONTAINER_WAITING_REASONS = [
		"CrashLoopBackOff",
		"CreateContainerConfigError",
		"CreateContainerError",
		"ErrImagePull",
		"ImageInspectError",
		"ImagePullBackOff",
		"InvalidImageName",
		"RunContainerError"
	] as Set

	/**
	 * This method logs Namespace and contining Pods to namespace.*/
	static void dumpNamespacesAndPods() {
		StringBuffer sb = new StringBuffer('##### K8s Dump ##### \n')
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			def pods = client.pods().inAnyNamespace().list().getItems()

			// sort: namespace, pod-name
			pods.sort { a, b -> (a.metadata?.namespace <=> b.metadata?.namespace) ?: (a.metadata?.name <=> b.metadata?.name)
			}

			// group by namespace
			def podsByNs = pods.groupBy { it.metadata?.namespace ?: "<no-namespace>" }

			podsByNs.each { ns, nsPods ->
				sb.append("\n=== Namespace: ${ns} (${nsPods.size()}) ===\n")
				nsPods.each { pod ->
					def name = pod.metadata?.name
					def phase = pod.status?.phase
					def node = pod.spec?.nodeName ?: "-"
					def startTime = pod.status?.startTime ?: "-"
					def restarts = (pod.status?.containerStatuses ?: []).sum { it?.restartCount ?: 0 } ?: 0

					sb.append(String.format("  %-60s  phase=%-10s restarts=%-3s node=%-25s start=%s",
						name, phase, restarts, node, startTime))
					sb.append("\n")
				}
			}
		}
		log.info sb.toString()
	}

	/**
	 * Executes command on container and returns result.
	 * @param client
	 * @param ns
	 * @param pod
	 * @param container
	 * @param cmd
	 * @return
	 */
	static String execAndGetStdout(KubernetesClient client,
		String ns,
		String pod,
		String container,
		String... cmd) {

		ByteArrayOutputStream out = new ByteArrayOutputStream()
		ByteArrayOutputStream err = new ByteArrayOutputStream()

		CountDownLatch finished = new CountDownLatch(1)
		AtomicReference<Throwable> failure = new AtomicReference<>()

		ExecListener listener = new ExecListener() {

			@Override
			void onClose(int code, String reason) {
				finished.countDown()
			}
		}

		try (ExecWatch watch = client.pods()
			.inNamespace(ns)
			.withName(pod)
			.inContainer(container)
			.writingOutput(out)
			.writingError(err)
			.usingListener(listener)
			.exec(cmd)) {

			Awaitility.await()
				.atMost(5, TimeUnit.MINUTES)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.until(() -> finished.getCount() == 0)

		} catch (Exception e) {
			throw new RuntimeException("Exec failed/timeout for pod " + ns + "/" + pod, e)
		}

		if (failure.get() != null) {
			throw new RuntimeException("Exec failure", failure.get())
		}

		String stderr = err.toString(StandardCharsets.UTF_8)
		if (!stderr.isBlank()) {
			log.error(stderr)
			throw new RuntimeException(stderr)
		}

		return out.toString(StandardCharsets.UTF_8)
	}

	/**
	 * Checks the current Kubernetes state once and verifies that every matching pod is running.
	 * Use a waitFor... variant when the tested resource may still be rolling out.
	 * @param namespace
	 * @param podNameStartsWith optional pod name prefix. Empty string matches all pods in the namespace.
	 */
	static boolean checkAllPodsRunningInNamespace(String namespace, String podNameStartsWith = "") {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Pod
			List<Pod> actualPods = client.pods().inNamespace(namespace).list().getItems().findAll { Pod pod ->
				pod.getMetadata().getName().startsWith(podNameStartsWith)
			}
			assert !actualPods.isEmpty(): "No pods found in namespace: ${namespace} with name ${podNameStartsWith}"
			failOnFatalPods(namespace, actualPods)
			List<Pod> notRunningPods = actualPods.findAll { Pod pod -> !isPodRunning(pod) }

			assert notRunningPods.isEmpty(): "These pods in ${namespace} are not yet running: ${describePods(notRunningPods)}"
			return true
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
			return false
		}
	}

	/**
	 * Waits until at least one matching pod exists and all matching pods are running.
	 */
	static boolean waitForAllPodsRunningInNamespace(String namespace,
		String podNameStartsWith = "",
		int timeout = DEFAULT_WAIT_MINUTES,
		TimeUnit timeoutUnit = TimeUnit.MINUTES) {
		Awaitility.await()
			.atMost(timeout, timeoutUnit)
			.pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
			.untilAsserted {
				checkAllPodsRunningInNamespace(namespace, podNameStartsWith)
			}
		return true
	}

	/**
	 * Checks the current Kubernetes state once and verifies one running pod for each expected name prefix.
	 * Extra pods in the namespace are ignored, which keeps the check stable during rollouts.
	 */
	static boolean checkPodPrefixesRunningInNamespace(String namespace, List<String> expectedPodPrefixes) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Pod> actualPods = client.pods().inNamespace(namespace).list().getItems()
			expectedPodPrefixes.each { String prefix ->
				List<Pod> matchingPods = actualPods.findAll { Pod pod -> pod.getMetadata().getName().startsWith(prefix) }
				failIfOnlyFatalPodsMatch(namespace, prefix, matchingPods)
			}

			List<String> missingPods = expectedPodPrefixes.findAll { String prefix ->
				!actualPods.any { Pod pod -> pod.getMetadata().getName().startsWith(prefix) }
			}
			assert missingPods.isEmpty(): "Missing these pods in ${namespace}: ${missingPods}"

			List<String> notRunningPodPrefixes = expectedPodPrefixes.findAll { String prefix ->
				List<Pod> matchingPods = actualPods.findAll { Pod pod -> pod.getMetadata().getName().startsWith(prefix) }
				!matchingPods.any { Pod pod -> isPodRunning(pod) }
			}
			assert notRunningPodPrefixes.isEmpty(): "No running pod found in ${namespace} for: ${notRunningPodPrefixes}. Current pods: ${describePods(actualPods)}"
			return true
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
			return false
		}
	}

	/**
	 * Waits until each expected pod name prefix has at least one running pod.
	 */
	static boolean waitForPodPrefixesRunningInNamespace(String namespace,
		List<String> expectedPodPrefixes,
		int timeout = DEFAULT_WAIT_MINUTES,
		TimeUnit timeoutUnit = TimeUnit.MINUTES) {
		Awaitility.await()
			.atMost(timeout, timeoutUnit)
			.pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
			.untilAsserted {
				checkPodPrefixesRunningInNamespace(namespace, expectedPodPrefixes)
			}
		return true
	}

	/**
	 * Checks the current Kubernetes state once using named pod matchers.
	 * Use this when simple prefixes are ambiguous, for example when one pod name is a prefix of another.
	 */
	static boolean checkPodsMatchingRunningInNamespace(String namespace, Map<String, Closure<Boolean>> expectedPods) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Pod> actualPods = client.pods().inNamespace(namespace).list().getItems()
			expectedPods.each { String expectedPod, Closure<Boolean> podNameMatches ->
				List<Pod> matchingPods = actualPods.findAll { Pod pod -> podNameMatches.call(pod.getMetadata().getName()) }
				failIfOnlyFatalPodsMatch(namespace, expectedPod, matchingPods)
			}

			List<String> missingPods = expectedPods.findAll { String expectedPod, Closure<Boolean> podNameMatches ->
				!actualPods.any { Pod pod -> podNameMatches.call(pod.getMetadata().getName()) }
			}.keySet() as List<String>
			assert missingPods.isEmpty(): "Missing these pods in ${namespace}: ${missingPods}"

			List<String> notRunningPods = expectedPods.findAll { String expectedPod, Closure<Boolean> podNameMatches ->
				List<Pod> matchingPods = actualPods.findAll { Pod pod -> podNameMatches.call(pod.getMetadata().getName()) }
				!matchingPods.any { Pod pod -> isPodRunning(pod) }
			}.keySet() as List<String>
			assert notRunningPods.isEmpty(): "No running pod found in ${namespace} for: ${notRunningPods}. Current pods: ${describePods(actualPods)}"
			return true
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
			return false
		}
	}

	/**
	 * Waits until every named pod matcher resolves to at least one running pod.
	 */
	static boolean waitForPodsMatchingRunningInNamespace(String namespace,
		Map<String, Closure<Boolean>> expectedPods,
		int timeout = DEFAULT_WAIT_MINUTES,
		TimeUnit timeoutUnit = TimeUnit.MINUTES) {
		Awaitility.await()
			.atMost(timeout, timeoutUnit)
			.pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
			.untilAsserted {
				checkPodsMatchingRunningInNamespace(namespace, expectedPods)
			}
		return true
	}

	/**
	 * Checks the current Kubernetes state once and verifies that all expected namespaces exist.
	 */
	static boolean checkNamespacesExist(List<String> expectedNamespaces) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Namespace> currentNamespaces = client.namespaces().list().getItems()
			List<String> missingNamespaces = expectedNamespaces.findAll { String expectedNamespace ->
				!currentNamespaces.any { Namespace currentNamespace -> currentNamespace.getMetadata().getName() == expectedNamespace }
			}
			assert missingNamespaces.isEmpty(): "Missing these Namespaces: ${missingNamespaces}"
			return true
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
			return false
		}
	}

	/**
	 * Waits until all expected namespaces exist.
	 */
	static boolean waitForNamespaces(List<String> expectedNamespaces,
		int timeout = DEFAULT_WAIT_MINUTES,
		TimeUnit timeoutUnit = TimeUnit.MINUTES) {
		Awaitility.await()
			.atMost(timeout, timeoutUnit)
			.pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
			.untilAsserted {
				checkNamespacesExist(expectedNamespaces)
			}
		return true
	}

	private static void failOnFatalPods(String namespace, Collection<Pod> pods) {
		Collection<Pod> fatalPods = pods.findAll { Pod pod -> isPodFatal(pod) }
		if (!fatalPods.isEmpty()) {
			throw new IllegalStateException("Pods in ${namespace} reached a terminal or unrecoverable state: ${describePods(fatalPods)}")
		}
	}

	private static void failIfOnlyFatalPodsMatch(String namespace, String expectedPod, Collection<Pod> matchingPods) {
		if (matchingPods.isEmpty() || matchingPods.any { Pod pod -> isPodRunning(pod) }) {
			return
		}

		if (matchingPods.every { Pod pod -> isPodFatal(pod) }) {
			throw new IllegalStateException(
				"No recoverable pod found in ${namespace} for ${expectedPod}. Matching pods: ${describePods(matchingPods)}"
			)
		}
	}

	private static boolean isPodRunning(Pod pod) {
		return pod.getStatus()?.getPhase() == RUNNING && !hasFatalContainerState(pod)
	}

	private static boolean isPodFatal(Pod pod) {
		String phase = pod.getStatus()?.getPhase()
		return phase == FAILED || phase == SUCCEEDED || hasFatalContainerState(pod)
	}

	private static boolean hasFatalContainerState(Pod pod) {
		return containerStatusesFor(pod).any { ContainerStatus status ->
			def waiting = status.getState()?.getWaiting()
			def terminated = status.getState()?.getTerminated()
			(waiting != null && FATAL_CONTAINER_WAITING_REASONS.contains(waiting.getReason()))
				|| (terminated != null && terminated.getExitCode() != null && terminated.getExitCode() != 0)
		}
	}

	private static List<ContainerStatus> containerStatusesFor(Pod pod) {
		List<ContainerStatus> statuses = []
		statuses.addAll(pod.getStatus()?.getInitContainerStatuses() ?: [])
		statuses.addAll(pod.getStatus()?.getContainerStatuses() ?: [])
		return statuses
	}

	private static String describePods(Collection<Pod> pods) {
		return pods.collect { Pod pod ->
			String podName = pod.getMetadata().getName()
			String phase = pod.getStatus()?.getPhase() ?: "<unknown>"
			List<ContainerStatus> containerStatuses = pod.getStatus()?.getContainerStatuses()
			String readyContainers = containerStatuses == null
				? "0/0"
				: "${containerStatuses.count { ContainerStatus status -> Boolean.TRUE == status.getReady() }}/${containerStatuses.size()}"
			String details = podProblemDetails(pod)
			"${podName}:${phase}:ready=${readyContainers}${details ? ":${details}" : ""}"
		}.join(', ')
	}

	private static String podProblemDetails(Pod pod) {
		List<String> details = []
		if (pod.getStatus()?.getReason()) {
			details << "reason=" + pod.getStatus().getReason()
		}
		if (pod.getStatus()?.getMessage()) {
			details << "message=" + shorten(pod.getStatus().getMessage())
		}
		containerStatusesFor(pod).each { ContainerStatus status ->
			String containerState = describeContainerState(status)
			if (containerState) {
				details << containerState
			}
		}
		return details.isEmpty() ? "" : "details=[${details.join('; ')}]"
	}

	private static String describeContainerState(ContainerStatus status) {
		def waiting = status.getState()?.getWaiting()
		if (waiting != null) {
			return "container=${status.getName()} waiting=${waiting.getReason() ?: '<unknown>'}${waiting.getMessage() ? " message=${shorten(waiting.getMessage())}" : ''}"
		}

		def terminated = status.getState()?.getTerminated()
		if (terminated != null) {
			return "container=${status.getName()} terminated=${terminated.getReason() ?: '<unknown>'} exit=${terminated.getExitCode()}"
		}

		return null
	}

	private static String shorten(String value) {
		return value.length() <= 160 ? value : "${value.take(157)}..."
	}
}