package com.cloudogu.gitops.integration;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Slf4j
public class TestK8sHelper {

	public static final int DEFAULT_WAIT_MINUTES = 5;
	public static final int DEFAULT_POLL_SECONDS = 5;
	public static final String RUNNING = "Running";
	public static final String FAILED = "Failed";
	public static final String SUCCEEDED = "Succeeded";
	public static final String COMPLETED = "Completed";
	public static final Set<String> FATAL_CONTAINER_WAITING_REASONS = Set.of(
		"CrashLoopBackOff",
		"CreateContainerConfigError",
		"CreateContainerError",
		"ErrImagePull",
		"ImageInspectError",
		"ImagePullBackOff",
		"InvalidImageName",
		"RunContainerError"
	);

	private TestK8sHelper() {
	}

	/**
	 * This method logs Namespace and contining Pods to namespace.
	 */
	public static void dumpNamespacesAndPods() {
		StringBuffer sb = new StringBuffer("##### K8s Dump ##### \n");
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Pod> pods = client.pods().inAnyNamespace().list().getItems();

			// sort: namespace, pod-name
			pods.sort(Comparator
				.comparing(
					(Pod pod) -> pod.getMetadata() == null ? null : pod.getMetadata().getNamespace(),
					Comparator.nullsFirst(Comparator.naturalOrder())
				)
				.thenComparing(
					pod -> pod.getMetadata() == null ? null : pod.getMetadata().getName(),
					Comparator.nullsFirst(Comparator.naturalOrder())
				));

			// group by namespace
			Map<String, List<Pod>> podsByNs = pods.stream()
												  .collect(Collectors.groupingBy(
													  pod -> pod.getMetadata() == null || pod.getMetadata().getNamespace() == null
														  ? "<no-namespace>"
														  : pod.getMetadata().getNamespace(),
													  LinkedHashMap::new,
													  Collectors.toList()
												  ));

			podsByNs.forEach((namespace, namespacePods) -> {
				sb.append("\n=== Namespace: ")
				  .append(namespace)
				  .append(" (")
				  .append(namespacePods.size())
				  .append(") ===\n");

				for (Pod pod : namespacePods) {
					String name = pod.getMetadata() == null ? null : pod.getMetadata().getName();
					String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
					String node = pod.getSpec() == null || pod.getSpec().getNodeName() == null
						? "-"
						: pod.getSpec().getNodeName();
					String startTime = pod.getStatus() == null || pod.getStatus().getStartTime() == null
						? "-"
						: pod.getStatus().getStartTime();

					int restarts = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
						? 0
						: pod.getStatus().getContainerStatuses().stream()
							 .filter(Objects::nonNull)
							 .map(ContainerStatus::getRestartCount)
							 .filter(Objects::nonNull)
							 .mapToInt(Integer::intValue)
							 .sum();

					sb.append(String.format(
						"  %-60s  phase=%-10s restarts=%-3s node=%-25s start=%s",
						name,
						phase,
						restarts,
						node,
						startTime
					));
					sb.append("\n");
				}
			});
		}
		log.info(sb.toString());
	}

	/**
	 * Executes command on container and returns result.
	 *
	 * @param client    Kubernetes client
	 * @param ns        namespace
	 * @param pod       pod name
	 * @param container container name
	 * @param cmd       command
	 * @return stdout of the command
	 */
	public static String execAndGetStdout(
		KubernetesClient client,
		String ns,
		String pod,
		String container,
		String... cmd
	) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();

		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		ExecListener listener = new ExecListener() {

			@Override
			public void onClose(int code, String reason) {
				finished.countDown();
			}
		};

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
					  .until(() -> finished.getCount() == 0);
		} catch (Exception e) {
			throw new RuntimeException("Exec failed/timeout for pod " + ns + "/" + pod, e);
		}

		if (failure.get() != null) {
			throw new RuntimeException("Exec failure", failure.get());
		}

		String stderr = err.toString(StandardCharsets.UTF_8);
		if (!stderr.isBlank()) {
			log.error(stderr);
			throw new RuntimeException(stderr);
		}

		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Checks the current Kubernetes state once and verifies that every matching pod is running.
	 * Use a waitFor... variant when the tested resource may still be rolling out.
	 *
	 * @param namespace namespace
	 */
	public static boolean checkAllPodsRunningInNamespace(String namespace) {
		return checkAllPodsRunningInNamespace(namespace, "");
	}

	/**
	 * Checks the current Kubernetes state once and verifies that every matching pod is running.
	 * Use a waitFor... variant when the tested resource may still be rolling out.
	 *
	 * @param namespace         namespace
	 * @param podNameStartsWith optional pod name prefix. Empty string matches all pods in the namespace.
	 */
	public static boolean checkAllPodsRunningInNamespace(String namespace, String podNameStartsWith) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Pod> actualPods = client.pods().inNamespace(namespace).list().getItems().stream()
										 .filter(pod -> pod.getMetadata().getName().startsWith(podNameStartsWith))
										 .collect(Collectors.toList());

			assertThat(actualPods)
				.withFailMessage("No pods found in namespace: %s with name %s", namespace, podNameStartsWith)
				.isNotEmpty();

			failOnFatalPods(namespace, actualPods);

			List<Pod> notRunningPods = actualPods.stream()
												 .filter(pod -> !isPodRunning(pod))
												 .collect(Collectors.toList());

			assertThat(notRunningPods)
				.withFailMessage("These pods in %s are not yet running: %s", namespace, describePods(notRunningPods))
				.isEmpty();
			return true;
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
			return false;
		}
	}

	public static boolean waitForAllPodsRunningInNamespace(String namespace) {
		return waitForAllPodsRunningInNamespace(namespace, "", DEFAULT_WAIT_MINUTES, TimeUnit.MINUTES);
	}

	public static boolean waitForAllPodsRunningInNamespace(String namespace, String podNameStartsWith) {
		return waitForAllPodsRunningInNamespace(
			namespace,
			podNameStartsWith,
			DEFAULT_WAIT_MINUTES,
			TimeUnit.MINUTES
		);
	}

	public static boolean waitForAllPodsRunningInNamespace(String namespace, String podNameStartsWith, int timeout) {
		return waitForAllPodsRunningInNamespace(namespace, podNameStartsWith, timeout, TimeUnit.MINUTES);
	}

	/**
	 * Waits until at least one matching pod exists and all matching pods are running.
	 */
	public static boolean waitForAllPodsRunningInNamespace(
		String namespace,
		String podNameStartsWith,
		int timeout,
		TimeUnit timeoutUnit
	) {
		Awaitility.await()
				  .atMost(timeout, timeoutUnit)
				  .pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
				  .untilAsserted(() -> checkAllPodsRunningInNamespace(namespace, podNameStartsWith));
		return true;
	}

	/**
	 * Checks the current Kubernetes state once and verifies one running pod for each expected name prefix.
	 * Extra pods in the namespace are ignored, which keeps the check stable during rollouts.
	 */
	public static boolean checkPodPrefixesRunningInNamespace(String namespace, List<String> expectedPodPrefixes) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Pod> actualPods = client.pods().inNamespace(namespace).list().getItems();

			for (String prefix : expectedPodPrefixes) {
				List<Pod> matchingPods = actualPods.stream()
												   .filter(pod -> pod.getMetadata().getName().startsWith(prefix))
												   .collect(Collectors.toList());
				failIfOnlyFatalPodsMatch(namespace, prefix, matchingPods);
			}

			List<String> missingPods = expectedPodPrefixes.stream()
														  .filter(prefix -> actualPods.stream()
																					  .noneMatch(pod -> pod.getMetadata().getName().startsWith(
				                                                                          prefix)))
														  .collect(Collectors.toList());

			assertThat(missingPods)
				.withFailMessage("Missing these pods in %s: %s", namespace, missingPods)
				.isEmpty();

			List<String> notRunningPodPrefixes = expectedPodPrefixes.stream()
																	.filter(prefix -> {
																		List<Pod> matchingPods = actualPods.stream()
																										   .filter(pod -> pod.getMetadata().getName().startsWith(
					                                                                                           prefix))
																										   .collect(
					                                                                                           Collectors.toList());
																		return matchingPods.stream().noneMatch(
					                                                        TestK8sHelper::isPodRunning);
																	})
																	.collect(Collectors.toList());

			assertThat(notRunningPodPrefixes)
				.withFailMessage(
					"No running pod found in %s for: %s. Current pods: %s",
					namespace,
					notRunningPodPrefixes,
					describePods(actualPods)
				)
				.isEmpty();
			return true;
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
			return false;
		}
	}

	public static boolean waitForPodPrefixesRunningInNamespace(
		String namespace,
		List<String> expectedPodPrefixes
	) {
		return waitForPodPrefixesRunningInNamespace(
			namespace,
			expectedPodPrefixes,
			DEFAULT_WAIT_MINUTES,
			TimeUnit.MINUTES
		);
	}

	public static boolean waitForPodPrefixesRunningInNamespace(
		String namespace,
		List<String> expectedPodPrefixes,
		int timeout
	) {
		return waitForPodPrefixesRunningInNamespace(namespace, expectedPodPrefixes, timeout, TimeUnit.MINUTES);
	}

	/**
	 * Waits until each expected pod name prefix has at least one running pod.
	 */
	public static boolean waitForPodPrefixesRunningInNamespace(
		String namespace,
		List<String> expectedPodPrefixes,
		int timeout,
		TimeUnit timeoutUnit
	) {
		Awaitility.await()
				  .atMost(timeout, timeoutUnit)
				  .pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
				  .untilAsserted(() -> checkPodPrefixesRunningInNamespace(namespace, expectedPodPrefixes));
		return true;
	}

	/**
	 * Checks the current Kubernetes state once using named pod matchers.
	 * Use this when simple prefixes are ambiguous, for example when one pod name is a prefix of another.
	 */
	public static boolean checkPodsMatchingRunningInNamespace(
		String namespace,
		Map<String, Predicate<String>> expectedPods
	) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Pod> actualPods = client.pods().inNamespace(namespace).list().getItems();

			for (Map.Entry<String, Predicate<String>> entry : expectedPods.entrySet()) {
				List<Pod> matchingPods = actualPods.stream()
												   .filter(pod -> entry.getValue().test(pod.getMetadata().getName()))
												   .collect(Collectors.toList());
				failIfOnlyFatalPodsMatch(namespace, entry.getKey(), matchingPods);
			}

			List<String> missingPods = expectedPods.entrySet().stream()
												   .filter(entry -> actualPods.stream()
																			  .noneMatch(pod -> entry.getValue().test(
				                                                                  pod.getMetadata().getName())))
												   .map(Map.Entry::getKey)
												   .collect(Collectors.toList());

			assertThat(missingPods)
				.withFailMessage("Missing these pods in %s: %s", namespace, missingPods)
				.isEmpty();

			List<String> notRunningPods = expectedPods.entrySet().stream()
													  .filter(entry -> {
														  List<Pod> matchingPods = actualPods.stream()
																							 .filter(pod -> entry.getValue().test(
					                                                                             pod.getMetadata().getName()))
																							 .collect(Collectors.toList());
														  return matchingPods.stream().noneMatch(TestK8sHelper::isPodRunning);
													  })
													  .map(Map.Entry::getKey)
													  .collect(Collectors.toList());

			assertThat(notRunningPods)
				.withFailMessage(
					"No running pod found in %s for: %s. Current pods: %s",
					namespace,
					notRunningPods,
					describePods(actualPods)
				)
				.isEmpty();
			return true;
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
			return false;
		}
	}

	public static boolean waitForPodsMatchingRunningInNamespace(
		String namespace,
		Map<String, Predicate<String>> expectedPods
	) {
		return waitForPodsMatchingRunningInNamespace(
			namespace,
			expectedPods,
			DEFAULT_WAIT_MINUTES,
			TimeUnit.MINUTES
		);
	}

	public static boolean waitForPodsMatchingRunningInNamespace(
		String namespace,
		Map<String, Predicate<String>> expectedPods,
		int timeout
	) {
		return waitForPodsMatchingRunningInNamespace(namespace, expectedPods, timeout, TimeUnit.MINUTES);
	}

	/**
	 * Waits until every named pod matcher resolves to at least one running pod.
	 */
	public static boolean waitForPodsMatchingRunningInNamespace(
		String namespace,
		Map<String, Predicate<String>> expectedPods,
		int timeout,
		TimeUnit timeoutUnit
	) {
		Awaitility.await()
				  .atMost(timeout, timeoutUnit)
				  .pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
				  .untilAsserted(() -> checkPodsMatchingRunningInNamespace(namespace, expectedPods));
		return true;
	}

	/**
	 * Checks the current Kubernetes state once and verifies that all expected namespaces exist.
	 */
	public static boolean checkNamespacesExist(List<String> expectedNamespaces) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Namespace> currentNamespaces = client.namespaces().list().getItems();

			List<String> missingNamespaces = expectedNamespaces.stream()
															   .filter(expectedNamespace -> currentNamespaces.stream()
																											 .noneMatch(
				                                                                                                 currentNamespace ->
																													 currentNamespace.getMetadata().getName().equals(
						                                                                                                 expectedNamespace)))
															   .collect(Collectors.toList());

			assertThat(missingNamespaces)
				.withFailMessage("Missing these Namespaces: %s", missingNamespaces)
				.isEmpty();
			return true;
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
			return false;
		}
	}

	public static boolean waitForNamespaces(List<String> expectedNamespaces) {
		return waitForNamespaces(expectedNamespaces, DEFAULT_WAIT_MINUTES, TimeUnit.MINUTES);
	}

	public static boolean waitForNamespaces(List<String> expectedNamespaces, int timeout) {
		return waitForNamespaces(expectedNamespaces, timeout, TimeUnit.MINUTES);
	}

	/**
	 * Waits until all expected namespaces exist.
	 */
	public static boolean waitForNamespaces(
		List<String> expectedNamespaces,
		int timeout,
		TimeUnit timeoutUnit
	) {
		Awaitility.await()
				  .atMost(timeout, timeoutUnit)
				  .pollInterval(DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
				  .untilAsserted(() -> checkNamespacesExist(expectedNamespaces));
		return true;
	}

	private static void failOnFatalPods(String namespace, Collection<Pod> pods) {
		Collection<Pod> fatalPods = pods.stream()
										.filter(TestK8sHelper::isPodFatal)
										.collect(Collectors.toList());

		if (!fatalPods.isEmpty()) {
			throw new IllegalStateException(
				"Pods in " + namespace + " reached a terminal or unrecoverable state: " + describePods(fatalPods)
			);
		}
	}

	private static void failIfOnlyFatalPodsMatch(
		String namespace,
		String expectedPod,
		Collection<Pod> matchingPods
	) {
		if (matchingPods.isEmpty() || matchingPods.stream().anyMatch(TestK8sHelper::isPodRunning)) {
			return;
		}

		if (matchingPods.stream().allMatch(TestK8sHelper::isPodFatal)) {
			throw new IllegalStateException(
				"No recoverable pod found in " + namespace + " for " + expectedPod
					+ ". Matching pods: " + describePods(matchingPods)
			);
		}
	}

	private static boolean isPodRunning(Pod pod) {
		String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
		return (RUNNING.equals(phase) || SUCCEEDED.equals(phase) || COMPLETED.equals(phase))
			&& !hasFatalContainerState(pod);
	}

	private static boolean isPodFatal(Pod pod) {
		String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
		return FAILED.equals(phase) || hasFatalContainerState(pod);
	}

	private static boolean hasFatalContainerState(Pod pod) {
		return containerStatusesFor(pod).stream().anyMatch(status -> {
			ContainerStateWaiting waiting = status.getState() == null ? null : status.getState().getWaiting();
			ContainerStateTerminated terminated = status.getState() == null ? null : status.getState().getTerminated();

			return (waiting != null && FATAL_CONTAINER_WAITING_REASONS.contains(waiting.getReason()))
				|| (terminated != null
				&& terminated.getExitCode() != null
				&& terminated.getExitCode() != 0);
		});
	}

	private static List<ContainerStatus> containerStatusesFor(Pod pod) {
		List<ContainerStatus> statuses = new ArrayList<>();
		if (pod.getStatus() == null) {
			return statuses;
		}
		if (pod.getStatus().getInitContainerStatuses() != null) {
			statuses.addAll(pod.getStatus().getInitContainerStatuses());
		}
		if (pod.getStatus().getContainerStatuses() != null) {
			statuses.addAll(pod.getStatus().getContainerStatuses());
		}
		return statuses;
	}

	private static String describePods(Collection<Pod> pods) {
		return pods.stream()
				   .map(pod -> {
					   String podName = pod.getMetadata().getName();
					   String phase = pod.getStatus() == null || pod.getStatus().getPhase() == null
						   ? "<unknown>"
						   : pod.getStatus().getPhase();
					   List<ContainerStatus> containerStatuses = pod.getStatus() == null
						   ? null
						   : pod.getStatus().getContainerStatuses();
					   String readyContainers;
					   if (containerStatuses == null) {
						   readyContainers = "0/0";
					   } else {
						   long readyCount = containerStatuses.stream()
															  .filter(status -> Boolean.TRUE.equals(status.getReady()))
															  .count();
						   readyContainers = readyCount + "/" + containerStatuses.size();
					   }
					   String details = podProblemDetails(pod);
					   return podName + ":" + phase + ":ready=" + readyContainers
						   + (details.isEmpty() ? "" : ":" + details);
				   })
				   .collect(Collectors.joining(", "));
	}

	private static String podProblemDetails(Pod pod) {
		List<String> details = new ArrayList<>();

		if (pod.getStatus() != null && pod.getStatus().getReason() != null
			&& !pod.getStatus().getReason().isEmpty()) {
			details.add("reason=" + pod.getStatus().getReason());
		}
		if (pod.getStatus() != null && pod.getStatus().getMessage() != null
			&& !pod.getStatus().getMessage().isEmpty()) {
			details.add("message=" + shorten(pod.getStatus().getMessage()));
		}

		for (ContainerStatus status : containerStatusesFor(pod)) {
			String containerState = describeContainerState(status);
			if (containerState != null && !containerState.isEmpty()) {
				details.add(containerState);
			}
		}

		return details.isEmpty() ? "" : "details=[" + String.join("; ", details) + "]";
	}

	private static String describeContainerState(ContainerStatus status) {
		ContainerStateWaiting waiting = status.getState() == null ? null : status.getState().getWaiting();
		if (waiting != null) {
			String reason = waiting.getReason() == null || waiting.getReason().isEmpty()
				? "<unknown>"
				: waiting.getReason();
			String message = waiting.getMessage() == null || waiting.getMessage().isEmpty()
				? ""
				: " message=" + shorten(waiting.getMessage());
			return "container=" + status.getName() + " waiting=" + reason + message;
		}

		ContainerStateTerminated terminated = status.getState() == null ? null : status.getState().getTerminated();
		if (terminated != null) {
			String reason = terminated.getReason() == null || terminated.getReason().isEmpty()
				? "<unknown>"
				: terminated.getReason();
			return "container=" + status.getName() + " terminated=" + reason + " exit=" + terminated.getExitCode();
		}

		return null;
	}

	private static String shorten(String value) {
		return value.length() <= 160 ? value : value.substring(0, 157) + "...";
	}
}
