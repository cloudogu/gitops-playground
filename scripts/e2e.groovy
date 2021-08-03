#!/usr/bin/env groovy

@Grapes([
        @Grab('org.slf4j:slf4j-api:1.7.32'),
        @Grab('org.slf4j:slf4j-simple:1.7.32'),
        //@Grab('ch.qos.logback:logback-classic:1.2.5'),
        @Grab('com.offbytwo.jenkins:jenkins-client:0.3.8'),
        @Grab('org.apache.httpcomponents:httpclient:4.5.13')
])

import com.offbytwo.jenkins.JenkinsServer
import com.offbytwo.jenkins.model.*
import groovy.cli.commons.CliBuilder
import groovy.cli.commons.OptionAccessor
import groovy.util.logging.Slf4j
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.apache.http.client.utils.URIBuilder


@Slf4j
class E2E {
    private static JenkinsServer jenkins
    private static Configuration config

    static void main(args) {
        config = CommandLineInterface.INSTANCE.parse(args)
        PipelineExecutor executor = new PipelineExecutor(config)

        try {
            jenkins = new JenkinsServer(new URI(config.url), config.username, config.password)
            List<Job> singleJobs = new ArrayList()
            List<JobWithDetails> detailedJobs = new ArrayList()

            // call build on the jobs hoping it will trigger the scan.
            // job -> job -> job
            jenkins.getJobs().each { Map.Entry<String, Job> job ->
                job.value.url = "${config.getUrl()}/job/${job.value.name}/"
                job.value.build(true)


                // check if it is folder job (OK we currently assume it is.. could be BOOM
                jenkins.getFolderJob(job.value).get().getJobs().each { Map.Entry<String, Job> j ->
                    j.value.url = "${job.value.url}job/${j.value.name}/"

                    jenkins.getFolderJob(j.value).get().getJobs().each { Map.Entry<String, Job> i ->
                        i.value.url = "${j.value.url}job/${i.value.name}/"
                        JobWithDetails detailed = i.value.details()
                        detailedJobs.add(detailed)
                        singleJobs.add(i.value)
                    }
                }

            }

            // this one is tricky if not known - executable status ref can be null
            // if it is null it will fail.. this means we need to keep querying the item on ref until it is present

            List<Future<Map<String, BuildResult>>> buildFutures = new ArrayList<Future<Map<String, BuildResult>>>()
            singleJobs.each { job ->
                buildFutures.add(executor.run(jenkins, job))
            }

            buildFutures.each { Future<Map<String, BuildResult>> build ->
                while (!build.isDone()) {
                    Thread.sleep(config.sleepInterval)
                }

                build.get().each { Map.Entry<String, BuildResult> b ->
                    log.info("[${b.key}] ${b.value.name()}")
                }
            }

        } catch (Exception err) {
            /*System.err << "Oh seems like something went wrong:\n"
            System.err << "${err.getStackTrace()}"
            System.exit 1 */
            throw err
        }
    }

    private static BuildWithDetails patchUrl(BuildWithDetails build, String url) {
        // TODO: implement a function to patch the url in details POJOs
        return null
    }

    private static String replaceHost(String host) {
        URI validUri = new URI(config.url)
        return new URIBuilder(URI.create(host)).setScheme(validUri.scheme).setHost(validUri.host).setPort(validUri.port).build().toString()
    }
}

class Configuration {

    private String url
    private String username
    private String password
    private int sleepInterval = 5000

    Configuration() {}

    String getUrl() { return this.url }

    String getUsername() { return this.username }

    String getPassword() { return this.password }

    int getSleepInterval() { return this.sleepInterval }

    void setUrl(String url) { this.url = url }

    void setUsername(String username) { this.username = username }

    void setPassword(String password) { this.password = password }

    void setSleepInterval(int interval) { this.sleepInterval = interval }

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

    Future<Map<String, BuildResult>> run(JenkinsServer js, Job job) {
        String executorId = new Random().with { (1..3).collect { (('a'..'z')).join()[nextInt((('a'..'z')).join().length())] }.join() }
        return executor.submit(() -> {
            log.info("[$executorId] Started by task executor.")
            log.debug("[$executorId] ${job.url}")
            QueueReference ref = job.build(true)
            BuildResult result = checkBuildResult(js, getQueueItemFromRef(js, ref, executorId), executorId)
            log.debug("[$executorId] Result: ${result.name()}")
            return Map.of(executorId, result)
        } as Callable) as Future<BuildResult>
    }

    private BuildResult checkBuildResult(JenkinsServer js, QueueItem item, String executorId) {
        while (js.getBuild(item).details().isBuilding()) {
            log.debug("[$executorId] Building..")
            Thread.sleep(configuration.sleepInterval)
        }

        return js.getBuild(item).details().getResult()
    }

    private QueueItem getQueueItemFromRef(JenkinsServer js, QueueReference ref, String executorId) {
        while (js.getQueueItem(ref).getExecutable() == null) {
            log.debug("[$executorId] Build has not yet started..")
            Thread.sleep(configuration.sleepInterval)
        }
        return js.getQueueItem(ref)
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
            _(longOpt: 'interval', args: 1, argName: 'Interval', 'Interval for waits')
            _(longOpt: 'debug', args: 0, argName: 'debug', 'debug')
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
        if (options.url) {
            config.url = options.url
        }
        if (options.user) {
            config.username = options.user
        }
        if (options.password) {
            config.password = options.password
        }
        if (options.interval) {
            config.sleepInterval = options.interval
        }

        String level = options.debug ? "DEBUG" : "INFO"
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level)

        if (!config.isValid()) {
            System.err << "Config given is invalid. Seems like you are missing one of the parameters. Use -h flag for help.\n"
            System.exit 1
        }

        return config
    }


}