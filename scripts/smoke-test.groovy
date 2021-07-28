#!/usr/bin/env groovy

@Grab('com.offbytwo.jenkins:jenkins-client:0.3.8')

import com.offbytwo.jenkins.JenkinsServer
import com.offbytwo.jenkins.model.Job
import com.offbytwo.jenkins.model.JobWithDetails

import groovy.cli.commons.CliBuilder
import groovy.cli.commons.OptionAccessor

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class Runner {

    static void main(args) {
        // parse cli options the 'new' way
        CommandLineInterface cli = CommandLineInterface.INSTANCE
        JenkinsConfiguration config = cli.parse(args)

        // create job runner instance
        PipelineExecutor pipelineExecutor  = new PipelineExecutor()

        // start jenkins server connection
        println(config.url)
        JenkinsServer jenkins = new JenkinsServer(new URI(config.url), config.username, config.password)

        // get jobs
        Map<String, Job> jobs = jenkins.getJobs()

        List<JobWithDetails> detailedJobs = new ArrayList<JobWithDetails>()

        // foreach job
        jobs.each { job ->
            job.value.build()
            detailedJobs << jenkins.getJob(job.value.getName())
        }

        detailedJobs.each {job ->
            println(job.getAllBuilds())
            //Future<PipelineState> state = pipelineExecutor.run(job)
            //while (!state.isDone())
            //    print(".")

            //println(state.get())
        }
        // start a thread starting to build
    }
}

class PipelineExecutor {
    private ExecutorService executor = Executors.newFixedThreadPool((int) (Runtime.getRuntime().availableProcessors() / 2))

    Future<PipelineState> run(JobWithDetails job) {
        return executor.submit(() -> {
            return PipelineState.IDLE
        }) as Future<PipelineState>
    }
}

enum PipelineState {
    IDLE, RUNNING, SUCCESS, FAILURE
}

class JenkinsConfiguration {

    private String url
    private String username
    private String password

    JenkinsConfiguration() {}

    String getUrl() { return this.url }

    String getUsername() { return this.username }

    String getPassword() { return this.password }

    void setUrl(String url) { this.url = url }

    void setUsername(String username) { this.username = username }

    void setPassword(String password) { this.password = password }

    boolean isValid() {
        return (
                url != null && !url.isEmpty()
                        && username != null && !username.isEmpty()
                        && password != null && !password.isEmpty())
    }
}

enum CommandLineInterface {
    INSTANCE

    CliBuilder cliBuilder

    CommandLineInterface() {
        cliBuilder = new CliBuilder(
                usage: 'smoke-test [<options>]',
                header: 'Options:',
                footer: 'And here we put footer text.'
        )
        // set the amount of columns the usage message will be wide
        cliBuilder.width = 80  // default is 74
        cliBuilder.with {
            h longOpt: 'help', 'Print this help text and exit.'
            _(longOpt: 'url', args: 1, argName: 'URL',
                    'Jenkins-URL')
            _(longOpt: 'user', args: 1, argName: 'User',
                    'Jenkins-User')
            _(longOpt: 'password', args: 1, argName: 'Password',
                    'Jenkins-Password')
        }
    }

    // TODO: return a config in here?
    JenkinsConfiguration parse(args) {
        JenkinsConfiguration config = new JenkinsConfiguration()
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
        if (!config.isValid()) {
            System.err << "Config given is invalid. Seems like you are missing one of the parameters. Use -h flag for help.\n"
            System.exit 1
        }

        return config
    }
}