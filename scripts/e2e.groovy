#!/usr/bin/env groovy

@Grapes([
        @Grab('org.slf4j:slf4j-api:1.7.32'),
        @Grab('org.slf4j:slf4j-simple:1.7.32'),
        @Grab('com.offbytwo.jenkins:jenkins-client:0.3.8'),
        @Grab('org.apache.httpcomponents:httpclient:4.5.13'),
])

import com.offbytwo.jenkins.JenkinsServer
import com.offbytwo.jenkins.model.*
import groovy.cli.commons.CliBuilder
import groovy.cli.commons.OptionAccessor
import groovy.util.logging.Slf4j
import org.apache.tools.ant.util.DateUtils

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Usage: `groovy e2e.groovy --url http://localhost:9090 --user admin --password admin
 *
 * Use --help for help
 * Optional parameters for wait interval and abort on failure.
 */
class E2E {
    static void main(args) {
        Configuration configuration = CommandLineInterface.INSTANCE.parse(args)
        PipelineExecutor executor = new PipelineExecutor(configuration)
        List<Future<PipelineResult>> buildFutures = new ArrayList<Future<PipelineResult>>()

        try {
            JenkinsHandler js = new JenkinsHandler(configuration)

            List<JobWithDetails> jobs = js.buildJobList()

            jobs.each { JobWithDetails job ->
                buildFutures.add(executor.run(js, job, configuration.retry))
            }

            // if any build is still running
            while (buildFutures.any { !it.isDone() }) {
                // check if there is a build which is done and has failed status
                Future<PipelineResult> resultFuture = buildFutures.find { it.isDone() && it.get().getBuild().getResult().name() == "FAILURE" }
                if (resultFuture != null){
                    //if retries are set, start the failed build new and delete the old one
                    if (resultFuture.get().retry > 0) {
                        //write log of failed build to fs
                        if (configuration.writeFailedLog) {
                            writeBuildLogToFile(resultFuture.get().getBuild())
                        }
                        int newRetry = resultFuture.get().retry - 1
                        buildFutures.add(executor.run(js, resultFuture.get().getJob(), newRetry))
                        buildFutures.remove(resultFuture)
                      // if abortonfail is true and no more retries left then kill the process
                    } else if (configuration.abortOnFail) {
                        //write log of failed build to fs
                        if (configuration.writeFailedLog) {
                            writeBuildLogToFile(resultFuture.get().getBuild())
                        }
                        println "A BUILD FAILED. ABORTING"
                        resultFuture.get().prettyPrint(true)
                        System.exit 1
                    }
                }
                Thread.sleep(configuration.sleepInterval)
            }

            buildFutures.each {
                it.get().prettyPrint(false)
                if (configuration.writeFailedLog && it.get().getBuild().getResult().name() == "FAILURE") {
                    writeBuildLogToFile(it.get().getBuild())
                }
            }


            int status = buildFutures.any { it.get().getBuild().getResult().name() == "FAILURE" } ? 1 : 0
            System.exit status

        } catch (Exception err) {
            System.err << "Unexpected error during execution of gitops playground e2e:\n"
            err.printStackTrace(System.err);
            System.exit 1
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
            writer.write(buildDetails.getConsoleOutputText())
        }

        println("written log file of failed job to: " + f.getAbsolutePath())
    }
}

@Slf4j
class JenkinsHandler {
    private JenkinsServer jenkins
    private Configuration configuration

    JenkinsHandler(Configuration configuration) {
        this.configuration = configuration
        this.jenkins = new JenkinsServer(new URI(configuration.url), configuration.username, configuration.password)
    }


    JenkinsServer get() { return this.jenkins }

    // Due to missing support of multibranch-pipelines in the java-jenkins-client we need to build up the jobs ourselves.
    // Querying the root folder and starting builds leads to a namespace scan.
    // After that we need to iterate through every job folder
    List<JobWithDetails> buildJobList() {
        List<JobWithDetails> jobs = new ArrayList<>()
        jenkins.getJobs().each { Map.Entry<String, Job> potentialNamespaceJob ->

            potentialNamespaceJob.value.url = "${configuration.getUrl()}/job/${potentialNamespaceJob.value.name}/"
            println "Trying to build job list for ${potentialNamespaceJob.value.name}"
            if (!jenkins.getFolderJob(potentialNamespaceJob.value).isPresent()) {
                println "Job ${potentialNamespaceJob.value.name} seems not to be a folder job. Skipping."
                return
            }

            // since there is no support for namespace scan; we call built on root folder and wait to discover branches.
            potentialNamespaceJob.value.build(true)

            var namespaceJob = waitForNamespaceJob(jenkins, potentialNamespaceJob.value)

            namespaceJob.getJobs().each { Map.Entry<String, Job> repoJob ->
                println("Checking the repo ${repoJob.getKey()}")
                jenkins.getFolderJob(repoJob.value).get().getJobs().each { Map.Entry<String, Job> branchJob ->
                    println("Checking the branch ${branchJob.getKey()}")
                    jobs.add(branchJob.value.details())
                }
            }
        }
        return jobs
    }

    BuildWithDetails waitForBuild(QueueItem item, String executorId) {
        while (jenkins.getBuild(item).details().isBuilding()) {
            log.debug("[$executorId] Building..")
            Thread.sleep(configuration.sleepInterval)
        }

        return jenkins.getBuild(item).details()
    }

    QueueItem getQueueItemFromRef(QueueReference ref, String executorId) {
        while (jenkins.getQueueItem(ref).getExecutable() == null) {
            log.debug("[$executorId] Build has not yet started..")
            Thread.sleep(configuration.sleepInterval)
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
            println "There are ${folderJob.getJobs().size()} jobs. These are:"
            folderJob.getJobs().each { Map.Entry<String, Job> repoJob -> println "${repoJob.getKey()}" }
        }
        return folderJob
    }
}

class Configuration {
    private String url
    private String username
    private String password
    private int sleepInterval = 2000
    private boolean abortOnFail = false
    private boolean writeFailedLog = false
    private int retry = 0

    Configuration() {}

    String getUrl() { return this.url }

    String getUsername() { return this.username }

    String getPassword() { return this.password }

    int getSleepInterval() { return this.sleepInterval }

    boolean getAbortOnFail() { return this.abortOnFail }

    boolean getWriteFailedLog() { return this.writeFailedLog }

    int getRetry() { return this.retry }

    void setUrl(String url) { this.url = url }

    void setUsername(String username) { this.username = username }

    void setPassword(String password) { this.password = password }

    void setSleepInterval(int interval) { this.sleepInterval = interval }

    void setAbortOnFail(boolean fail) { this.abortOnFail = fail }

    void setWriteFailedLog(boolean writeFailedLog) { this.writeFailedLog = writeFailedLog }

    void setRetry(int retry) { this.retry = retry }


    boolean isValid() {
        return (
                url != null && !url.isEmpty()
                        && username != null && !username.isEmpty()
                        && password != null && !password.isEmpty())
    }
}

/**
 * Executor
 *
 * Handles the parallel execution of the builds and observes them.
 * Returns a future holding the result and build information.
 */
@Slf4j
class PipelineExecutor {
    private ExecutorService executor = Executors.newFixedThreadPool((int) (Runtime.getRuntime().availableProcessors() / 2))
    private Configuration configuration

    PipelineExecutor(Configuration configuration) {
        this.configuration = configuration
    }

    Future<PipelineResult> run(JenkinsHandler js, JobWithDetails job, int retry) {
        String executorId = new Random().with { (1..3).collect { (('a'..'z')).join()[nextInt((('a'..'z')).join().length())] }.join() }
        return executor.submit(() -> {
            println "[$executorId] ${StringUtils.reduceToName(job.url)} started.."
            QueueReference ref = job.build(true)
            BuildWithDetails details = js.waitForBuild(js.getQueueItemFromRef(ref, executorId), executorId)
            return new PipelineResult(executorId, job, details, retry)
        } as Callable) as Future<PipelineResult>
    }
}

class PipelineResult {
    private String executorId
    private JobWithDetails job
    private BuildWithDetails build
    private int retry

    PipelineResult(String id, JobWithDetails job, BuildWithDetails build, int retry) {
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

    JobWithDetails getJob() {
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
 * CLI-args definition and handling.
 */
enum CommandLineInterface {
    INSTANCE

    CliBuilder cliBuilder

    CommandLineInterface() {
        cliBuilder = new CliBuilder(
                usage: 'e2e [<options>]',
                header: 'Options:',
                footer: 'And here we put footer text.'
        )
        // set the amount of columns the usage message will be wide
        cliBuilder.width = 80  // default is 74
        cliBuilder.with {
            h longOpt: 'help', 'Print this help text and exit.'
            _(longOpt: 'url', args: 1, argName: 'URL', 'Jenkins-URL')
            _(longOpt: 'user', args: 1, argName: 'User', 'Jenkins-User')
            _(longOpt: 'password', args: 1, argName: 'Password', 'Jenkins-Password')
            _(longOpt: 'fail', argName: 'fail', 'Exit on first build failure')
            _(longOpt: 'writeFailedLog', argName: 'writeFailedLog', 'Writes a log file for each failed build to the folder playground-logs-of-failed-jobs/')
            _(longOpt: 'retry', args: 1, argName: 'retry', 'Retries failed builds x time')
            _(longOpt: 'interval', args: 1, argName: 'Interval', 'Interval for waits')
            _(longOpt: 'debug', argName: 'debug', 'Set log level to debug')
        }
    }

    Configuration parse(args) {
        Configuration config = new Configuration()
        OptionAccessor options = cliBuilder.parse(args)

        if (!options) {
            System.err << "Error while parsing command-line options.\n"
            System.exit 1
        }

        if (options.h) {
            cliBuilder.usage()
            System.exit 0
        }

        if (options.url)
            config.url = options.url

        if (options.user)
            config.username = options.user

        if (options.password)
            config.password = options.password

        if (options.interval)
            config.sleepInterval = options.interval

        if (options.retry)
            config.retry = Integer.parseInt(options.retry)

        String level = options.debug ? "debug" : "info"
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level)

        config.abortOnFail = options.fail ? true : false

        config.writeFailedLog = options.writeFailedLog ? true : false

        if (!config.isValid()) {
            System.err << "Config given is invalid. Seems like you are missing one of the parameters. Use -h flag for help.\n"
            System.exit 1
        }

        return config
    }
}

/**
 * Color palette for CLI colorization.
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

class StringUtils {

    static String reduceToName(String input) {
        return input.substring(
                input.findIndexValues(0, { it -> it == "/" }).get(3).toInteger() + 1,
                input.length() - 1)
                .replaceAll("job", "").replaceAll("//", " » ").trim()
    }
}