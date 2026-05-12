package com.cloudogu.gitops.integration

import static org.assertj.core.api.Assertions.fail

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import groovy.util.logging.Slf4j

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
	 * Test defined namespace and check if all pods running or specific pod. Pod is find by name which startWith...
	 * @param namespace
	 * @param podNameStartsWith
	 */
	static boolean checkAllPodsRunningInNamespace(String namespace, String podNameStartsWith = "") {
		String running = "Running"
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Pod
			def actualPods = client.pods().inNamespace(namespace).list().getItems().findAll { it.metadata.name.startsWith(podNameStartsWith) }
			assert !actualPods.isEmpty(): "No pods found in namespace: ${namespace} with name ${podNameStartsWith}"
			def notRunningPods = actualPods.findAll { pod -> pod.getStatus().getPhase() != running }

			assert notRunningPods.isEmpty(): "These pods in ${namespace} are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"
			return true
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
			return false
		}
	}
}