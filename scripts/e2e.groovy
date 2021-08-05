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

class E2E {
    static void main(args) {
        Configuration configuration = CommandLineInterface.INSTANCE.parse(args)
        PipelineExecutor executor = new PipelineExecutor(configuration)


        try {
            JenkinsHandler js = new JenkinsHandler(configuration)

            List<JobWithDetails> jobs = js.buildJobList()

            List<Future<PipelineResult>> buildFutures = new ArrayList<Future<PipelineResult>>()
            jobs.each { JobWithDetails job ->
                buildFutures.add(executor.run(js.get(), job))
            }

            while (buildFutures.any { !it.isDone() }) {
                if (configuration.abortOnFail && buildFutures.any { result ->
                    result.isDone() && result.get().getBuild().getResult().name() == "FAILURE"
                }) {
                    println "A BUILD FAILED. ABORTING"
                    buildFutures.find {it.isDone()}.get().prettyPrint(true)
                    System.exit 1
                }

                Thread.sleep(configuration.sleepInterval)
            }
        } catch (Exception err) {
            //System.err << "Oh seems like something went wrong:\n"
            //System.err << "${err.getStackTrace()}"
            //System.exit 1
            throw err
        }

        println "Successfully built all pipelines."
        System.exit 0
    }
}

@Slf4j
class JenkinsHandler {
    private JenkinsServer js
    private Configuration configuration

    JenkinsHandler(Configuration configuration) {
        this.configuration = configuration
        this.js = new JenkinsServer(new URI(configuration.url), configuration.username, configuration.password)
    }

    JenkinsServer get() { return this.js }

    // TODO: We should check if its really a folder job. Currently we just assume it which is stupid
    List<JobWithDetails> buildJobList() {
        List<JobWithDetails> jobs = new ArrayList<>()
        js.getJobs().each { Map.Entry<String, Job> job ->
            job.value.url = "${configuration.getUrl()}/job/${job.value.name}/"
            job.value.build(true)

            js.getFolderJob(job.value).get().getJobs().each { Map.Entry<String, Job> j ->
                j.value.url = "${job.value.url}job/${j.value.name}/"

                js.getFolderJob(j.value).get().getJobs().each { Map.Entry<String, Job> i ->
                    i.value.url = "${j.value.url}job/${i.value.name}/"
                    jobs.add(i.value.details())
                }
            }
        }
        return jobs
    }
}

class Configuration {

    private String url
    private String username
    private String password
    private int sleepInterval = 2000
    private boolean abortOnFail = false

    Configuration() {}

    String getUrl() { return this.url }

    String getUsername() { return this.username }

    String getPassword() { return this.password }

    int getSleepInterval() { return this.sleepInterval }

    boolean getAbortOnFail() { return this.abortOnFail }

    void setUrl(String url) { this.url = url }

    void setUsername(String username) { this.username = username }

    void setPassword(String password) { this.password = password }

    void setSleepInterval(int interval) { this.sleepInterval = interval }

    void setAbortOnFail(boolean fail) { this.abortOnFail = fail }


    boolean isValid() {
        return (
                url != null && !url.isEmpty()
                        && username != null && !username.isEmpty()
                        && password != null && !password.isEmpty())
    }
}

@Slf4j
class PipelineExecutor {
    private ExecutorService executor = Executors.newFixedThreadPool((int) (Runtime.getRuntime().availableProcessors() / 2))
    private Configuration configuration

    PipelineExecutor(Configuration configuration) {
        this.configuration = configuration
    }

    Future<PipelineResult> run(JenkinsServer js, JobWithDetails job) {
        String executorId = new Random().with { (1..3).collect { (('a'..'z')).join()[nextInt((('a'..'z')).join().length())] }.join() }
        return executor.submit(() -> {
            println "[$executorId] ${StringUtils.reduceToName(job.url)} started.."
            QueueReference ref = job.build(true)
            BuildWithDetails details = waitForBuild(js, getQueueItemFromRef(js, ref, executorId), executorId)
            return new PipelineResult(executorId, job, details)
        } as Callable) as Future<PipelineResult>
    }

    private BuildWithDetails waitForBuild(JenkinsServer js, QueueItem item, String executorId) {
        while (js.getBuild(item).details().isBuilding()) {
            log.debug("[$executorId] Building..")
            Thread.sleep(configuration.sleepInterval)
        }

        return js.getBuild(item).details()
    }

    private QueueItem getQueueItemFromRef(JenkinsServer js, QueueReference ref, String executorId) {
        while (js.getQueueItem(ref).getExecutable() == null) {
            log.debug("[$executorId] Build has not yet started..")
            Thread.sleep(configuration.sleepInterval)
        }
        return js.getQueueItem(ref)
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

class PipelineResult {
    private final String defaultColor = ""
    private String executorId
    private JobWithDetails job
    private BuildWithDetails build

    private String fullDisplayName
    private String url
    private int result
    private String resultName
    private String duration
    private String consoleOutputText
    private String color

    /*
    FAILURE, UNSTABLE, REBUILDING, BUILDING,

    This means a job was already running and has been aborted.

    ABORTED,

    SUCCESS,
     */


    PipelineResult(String id, JobWithDetails job, BuildWithDetails build) {
        this.executorId = id
        this.job = job
        this.build = build
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

    void prettyPrint(boolean minify = true) {
        String out = ""
        if (minify)
            out = "[${this.getExecutorId()}] ${this.getBuild().fullDisplayName} | ${this.getBuild().getResult().name()} | ${DateUtils.formatElapsedTime(this.getBuild().duration)}"

        else {
            out = """
                [${this.getExecutorId()}] 
                ${this.getBuild().fullDisplayName}
                Build has finished: ${this.getBuild().getResult().name()} in ${DateUtils.formatElapsedTime(this.getBuild().duration)}.
            """
        }
        println out
    }
}

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

        String level = options.debug ? "debug" : "info"
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level)

        config.abortOnFail = options.fail ? true : false

        if (!config.isValid()) {
            System.err << "Config given is invalid. Seems like you are missing one of the parameters. Use -h flag for help.\n"
            System.exit 1
        }

        return config
    }
}