#!/usr/bin/env groovy
package com.cloudogu.gitops.integration

import com.offbytwo.jenkins.JenkinsServer
import com.offbytwo.jenkins.model.*
import groovy.util.logging.Slf4j
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Node
import io.kubernetes.client.openapi.models.V1NodeAddress
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.apache.tools.ant.util.DateUtils
import org.junit.jupiter.api.Test

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail 
/**
 * Usage: `groovy com.cloudogu.gitops.integration.E2EIT.groovy --url http://localhost:9090 --user admin --password admin
 *
 * Use --help for help
 * Optional parameters for wait interval and abort on failure.
 */
@Slf4j
class JenkinsPipelineTestLongIT {

    int numberOfExampleRepos = 3
    boolean abortOnFail = true
    int retry = 2;

    static int sleepInterval = 2000
    //to get IP one time via kubernetes - lazy init.
    static String INTERNAL_IP = 'InternalIP'
    static String CURRENT_URL = null

    static String findUrl() {
        if (!CURRENT_URL) {
            if (!Utils.isRunningInContainer()) {
                // When the tests are running in a container, e.g. CI build (see Jenkinsfile)
                // jenkins.localhost will not work there. 
                // So, we use the IP of the single node k3d cluster and find out the nodePort of the Jenkins Service
                String kubeConfigPath = System.getenv("HOME") + "/.kube/config"
                ApiClient client =
                        ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build()
                // set the global default api-client to the out-of-cluster one from above
                Configuration.setDefaultApiClient(client)
                CoreV1Api api = new CoreV1Api()
    
                CURRENT_URL = "http://${findCurrentIp(api)}:${findPort(api)}"
                println "Running in container. Setting current Jenkin URL to IP: ${CURRENT_URL}"
            } else {
                // Using ingress is the only option to make the test work on local dev machines on all OSes: Windows, MacOS and Linux
                // Obviously only when the ingress controller was deployed
                CURRENT_URL = "http://jenkins.localhost"
                println "Not Running in Container. Setting current Jenkin URL to ingress: ${CURRENT_URL}"
            }
        }
        return CURRENT_URL
    }

    protected static String findCurrentIp(CoreV1Api api) {
        def nodes = api.listNode().execute()
        def items = nodes.getItems()
        for (V1Node node : items) {
            def addresses = node.status.addresses
            for (V1NodeAddress address : addresses) {
                if (INTERNAL_IP == address.type) {
                    return address.address
                }
            }
        }
        throw new RuntimeException('Unable to find IP Address of local cluster node')
    }

    static Integer findPort(CoreV1Api api) {
        def service = api.readNamespacedService('jenkins', 'jenkins').execute()
        return service.getSpec().getPorts().get(0).getNodePort()
    }

    @Test
    void ipShouldNotBeNull() {
        assertThat(findUrl()).isNotNull()
        assertThat(findUrl()).contains('.') //
    }

    @Test
    void checkExpectedJenkinsJobs() {
        JenkinsHandler js = new JenkinsHandler(findUrl())
        List<Job> jobs = js.buildJobList()
        assertThat(jobs.size()).isEqualTo(numberOfExampleRepos)
    }

    @Test
    void checkJenkinsIsAvailable() {
        JenkinsHandler js = new JenkinsHandler(findUrl())
        JenkinsServer jenkins = js.get()
        assertThat(jenkins).isNotNull()
        assertThat(jenkins.running).isTrue()
    }


    @Test
    void wholeJenkinsPipelineTest() {
        PipelineExecutor executor = new PipelineExecutor()
        List<Future<PipelineResult>> buildFutures = new ArrayList<Future<PipelineResult>>()

        try {
            JenkinsHandler js = new JenkinsHandler(findUrl())

            List<Job> jobs = js.buildJobList()
            assertThat(jobs.size()).isEqualTo(numberOfExampleRepos)
            jobs.each { Job job ->
                buildFutures.add(executor.run(js, job, retry))
            }

            assertThat(buildFutures.size()).isEqualTo(numberOfExampleRepos)

            // if any build is still running
            while (buildFutures.any { !it.isDone() }) {
                // check if there is a build which is done and has failed status
                Future<PipelineResult> resultFuture = buildFutures.find { it.isDone() && it.get().getBuild().getResult().name() == "FAILURE" }
                if (resultFuture != null) {
                    //if retries are set, start the failed build new and delete the old one
                    if (resultFuture.get().retry > 0) {
                        //write log of failed build to fs
                        writeBuildLogToFile(resultFuture.get().getBuild())
                        int newRetry = resultFuture.get().retry - 1
                        buildFutures.add(executor.run(js, resultFuture.get().getJob(), newRetry))
                        buildFutures.remove(resultFuture)
                        // if abortOnFail is true and no more retries left then kill the process
                    } else if (abortOnFail) {
                        //write log of failed build to fs
                        writeBuildLogToFile(resultFuture.get().getBuild())
                        println "A BUILD FAILED. ABORTING"
                        resultFuture.get().prettyPrint(true)
                        fail("A BUILD FAILED. ABORTING")
                    }
                }
                Thread.sleep(sleepInterval)
            }

            buildFutures.each {
                it.get().prettyPrint(false)
                if (it.get().getBuild().getResult().name() == "FAILURE") {
                    writeBuildLogToFile(it.get().getBuild())
                }
                assertThat(it.get().getBuild().getResult().name()).isEqualTo("SUCCESS")
            }


            int status = buildFutures.any { it.get().getBuild().getResult().name() == "FAILURE" } ? 1 : 0
            assertThat(status).isEqualTo(0)

        } catch (Exception err) {
            fail("Unexpected error during execution of gitops playground e2e:\n", err)
        }
    }

    static void writeBuildLogToFile(BuildWithDetails buildDetails) {
        
        String directoryName = "playground-logs-of-failed-jobs/"

        File directory = new File(directoryName);
        if (!directory.exists()) {
            directory.mkdir();
        }

        File f = new File(directoryName + buildDetails.getFullDisplayName() + ".log")
        f.withWriter("utf-8") { writer ->
            writer.write(Utils.changeHost(buildDetails, CURRENT_URL).getConsoleOutputText())
        }

        println("written log file of failed job to: " + f.getAbsolutePath())

    }
}

class JenkinsHandler {
    private JenkinsServer jenkins
    String url

    JenkinsHandler(String url) {
        this.url = url
        this.jenkins = new JenkinsServer(new URI(url), "admin", "admin")
    }

    JenkinsServer get() { return this.jenkins }

    // Due to missing support of multibranch-pipelines in the java-jenkins-client we need to build up the jobs ourselves.
    // Querying the root folder and starting builds leads to a namespace scan.
    // After that we need to iterate through every job folder
    List<Job> buildJobList() {
        List<Job> jobs = new ArrayList<>()
        jenkins.getJobs().each { Map.Entry<String, Job> potentialNamespaceJob ->

            potentialNamespaceJob.value.url = "${url}/job/${potentialNamespaceJob.value.name}/"
            println "Trying to build job list for ${potentialNamespaceJob.value.name}, URL: ${potentialNamespaceJob.value.url}"
            if (!jenkins.getFolderJob(potentialNamespaceJob.value).isPresent()) {
                println "Job ${potentialNamespaceJob.value.name} seems not to be a folder job. Skipping."
                return
            }

            // since there is no support for namespace scan; we call built on root folder and wait to discover branches.
            potentialNamespaceJob.value.build(true)

            var namespaceJob = waitForNamespaceJob(jenkins, potentialNamespaceJob.value)

            namespaceJob.getJobs().each { Map.Entry<String, Job> repoJob ->
                repoJob.value.url = "${url}/job/${potentialNamespaceJob.value.name}/job/${repoJob.value.name}"
                println("Checking the repo ${potentialNamespaceJob.value.name}/${repoJob.key} at URL: ${repoJob.value.url}")

                jenkins.getFolderJob(repoJob.value).get().getJobs().each { Map.Entry<String, Job> branchJob ->
                    branchJob.value.url = "${url}/job/${potentialNamespaceJob.value.name}/job/${repoJob.value.name}/job/${branchJob.value.name}/"
                    println("Added Branch job ${potentialNamespaceJob.value.name}/${repoJob.key}/${branchJob.key}, URL: ${branchJob.value.url}")
                    jobs.add(branchJob.value)
                    // We have to set the URL (to avoid exception
                    // "Connect to jenkins.localhost:80 [jenkins.localhost/127.0.0.1, jenkins.localhost/0:0:0:0:0:0:0:1] failed: Connection refused").
                    // But setting the URL in JobWithDetails is not possible (Groovyc: [Static type checking] - Cannot set read-only property: url),
                    // so use the one without details 🤷
                    //println("Checking the branch ${potentialNamespaceJob.value.name}/${repoJob.key}/${branchJob.key}, URL: ${branchJob.value.url}")
                    // Job jobDetails = branchJob.value.details()
                    //jobDetails.url = branchJob.value.url
                    //jobs.add(jobDetails)
                }
            }
        }
        return jobs
    }

    BuildWithDetails waitForBuild(QueueItem item, String executorId) {
        Build build = jenkins.getBuild(item)
        def buildWithAdaptedUrl = new Build(build.number, Utils.changeHost(build.url, url))
        buildWithAdaptedUrl.setClient(build.client)
        println("Getting build details from URL: ${buildWithAdaptedUrl.url}")
        while (buildWithAdaptedUrl.details().isBuilding()) {
            // log.debug("[$executorId] Building..")
           Thread.sleep(JenkinsPipelineTestLongIT.sleepInterval)
        } 

        return buildWithAdaptedUrl.details()
    }

    QueueItem getQueueItemFromRef(QueueReference ref, String executorId) {
        while (jenkins.getQueueItem(ref).getExecutable() == null) {
            // log.debug("[$executorId] Build has not yet started..")
            Thread.sleep(JenkinsPipelineTestLongIT.sleepInterval)
        }
        return jenkins.getQueueItem(ref)

    }

    FolderJob waitForNamespaceJob(JenkinsServer server, Job job) {
        // Scanning namespace takes several seconds to complete. Example:
        // [Wed Sep 08 13:52:35 CEST 2021] Finished organization scan. Scan took 13 sec
        int count = 0;
        int maxTries = 20;
        FolderJob folderJob = server.getFolderJob(job).get()
        while (folderJob.getJobs().size() == 0) {
            println "Folder ${job.name} does not contain jobs. Waiting ${count + 1} / ${maxTries}..."
            Thread.sleep(3000)

            // Refresh value
            folderJob = server.getFolderJob(job).get()
            if (++count == maxTries) {
                break
            }
        }
        if (folderJob.getJobs().size() == 0) {
            println "WARNING: Job ${job.name} is a folder but does not include jobs."
        } else {
            println "Found repositories. Waiting a little more so that all repositories have been discovered"
            Thread.sleep(30000)
            folderJob = server.getFolderJob(job).get()
            println "There are ${folderJob.jobs.size()} jobs. These are:"
            folderJob.getJobs().each { Map.Entry<String, Job> repoJob -> println "${repoJob.key}, URL: ${repoJob.value.url}" }
        }
        return folderJob
    }
}


/**
 * Executor
 *
 * Handles the parallel execution of the builds and observes them.
 * Returns a future holding the result and build information.
 */

class PipelineExecutor {
    private ExecutorService executor = Executors.newFixedThreadPool((int) (Runtime.getRuntime().availableProcessors() / 2))

    PipelineExecutor() {
    }

    Future<PipelineResult> run(JenkinsHandler js, Job job, int retry) {

        String executorId = defineIdExecutorID()
        return executor.submit(() -> {
            println "[$executorId] ${Utils.reduceToName(job.url)} started.. via URL: ${job.url}"
            QueueReference ref = job.build(true)
            def queueItem = js.getQueueItemFromRef(ref, executorId)
            queueItem.executable.url = Utils.changeHost(queueItem.executable.url, js.url)
            println("Waiting for build ${queueItem.executable.url }")
            BuildWithDetails details = js.waitForBuild(queueItem, executorId)
            return new PipelineResult(executorId, job, details, retry)
        } as Callable) as Future<PipelineResult>
    }

    static String defineIdExecutorID() {
        Random executorId = new Random()
        String result = executorId.with {
            Range<Integer> three = (1..3)
            three.collect {
                int aToZ2 = ('a'..'z').join('').length()
                Range<String> aToZ = ('a'..'z')
                aToZ.join('')[new Random().nextInt(aToZ2)]
            }.join('')
        }
        return result
    }

}

class PipelineResult {
    private String executorId
    private Job job
    private BuildWithDetails build
    private int retry

    PipelineResult(String id, Job job, BuildWithDetails build, int retry) {
        this.executorId = id
        this.job = job
        this.build = build
        this.retry = retry
    }

    String getExecutorId() {
        return executorId
    }

    void setExecutorId(String executorId) {
        this.executorId = executorId
    }

    Job getJob() {
        return job
    }

    void setJob(JobWithDetails job) {
        this.job = job
    }

    BuildWithDetails getBuild() {
        return build
    }

    void setBuild(BuildWithDetails build) {
        this.build = build
    }

    void setRetry(int retry) {
        this.retry = retry
    }

    int getRetry() {
        return retry
    }

    private Color getPrintColor() {
        return List.of("FAILURE", "ABORTED").contains(build.getResult().name()) ? Color.RED_BOLD : Color.GREEN_BOLD
    }

    void prettyPrint(boolean minify = true) {
        if (minify)
            println "${getPrintColor()}[${this.getExecutorId()}] ${this.getBuild().fullDisplayName} | ${this.getBuild().getResult().name()} | ${DateUtils.formatElapsedTime(this.getBuild().duration)}${Color.RESET}"
        else {

            println """
                ${getPrintColor()}[${this.getExecutorId()}] 
                ${this.getBuild().fullDisplayName}
                Build has finished: ${this.getBuild().getResult().name()} in ${DateUtils.formatElapsedTime(this.getBuild().duration)}.${Color.RESET}
            """
        }
    }
}


/**
 * com.cloudogu.gitops.integration.Color palette for CLI colorization.
 */
enum Color {
    RESET("\033[0m"),
    BLACK_BOLD("\033[1;30m"),
    RED_BOLD("\033[1;31m"),
    GREEN_BOLD("\033[1;32m"),
    YELLOW_BOLD("\033[1;33m"),
    BLUE_BOLD("\033[1;34m"),
    MAGENTA_BOLD("\033[1;35m"),
    CYAN_BOLD("\033[1;36m"),
    WHITE_BOLD("\033[1;37m"),

    private final String code

    Color(String code) {
        this.code = code
    }

    @Override
    String toString() {
        return code
    }
}

class Utils {

    static String reduceToName(String input) {
        return input.substring(
                input.findIndexValues(0, { it -> it == "/" }).get(3).toInteger() + 1,
                input.length() - 1)
                .replaceAll("job", "").replaceAll("//", " » ").trim()
    }
    
    static String changeHost(String url, String newHost) {
        url.replaceFirst("https?://[^/]*/", newHost + "/")
    }

    static BuildWithDetails changeHost(BuildWithDetails buildWithDetails, String host) {
        new BuildWithDetailsWithChangableUrl(buildWithDetails, changeHost(buildWithDetails.url, host))
    }

    static boolean isRunningInContainer() {
        File cgroupFile = new File("/proc/1/cgroup")
        if (cgroupFile.exists()) {
            return cgroupFile.text.contains("docker") || cgroupFile.text.contains("containerd")
        }
        return false
    }

    static class BuildWithDetailsWithChangableUrl extends BuildWithDetails {
        BuildWithDetailsWithChangableUrl(BuildWithDetails details, String url) {
            super(details)
            this.url = url
        }
    }
}