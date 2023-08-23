package com.cloudogu.gitops.cli

import com.cloudogu.gitops.dependencyinjection.JenkinsFactory
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JenkinsConfiguration
import com.cloudogu.gitops.jenkins.JobManager
import com.cloudogu.gitops.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.jenkins.UserManager
import com.cloudogu.gitops.utils.CommandExecutor
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(name = 'jenkins', description = 'CLI-tool to configure jenkins.',
        mixinStandardHelpOptions = true)
@Slf4j
class JenkinsCli {
    @Command(name = "add-user", description = "adds user to jenkins", mixinStandardHelpOptions = true)
    void addUser(
            @Parameters(paramLabel = "username", description = "The username to create") String username,
            @Parameters(paramLabel = "password", description = "The user's password") String password,
            @Mixin OptionsMixin options
    ) {
        def userManager = createApplicationContext(options).getBean(UserManager)
        if (userManager.isUsingCasSecurityRealm()) {
            log.trace("Using CAS Security Realm. Must not create user.")
        } else {
            userManager.createUser(username, password)
        }
    }

    @Command(name = "grant-permission", description = "grants permission to jenkins user", mixinStandardHelpOptions = true)
    void grantPermission(
            @Parameters(paramLabel = "username", description = "The username to grant the permission to")
            String username,
            @Parameters(paramLabel = "permission", description = "The permission to grant. Possible values: \${COMPLETION-CANDIDATES}")
            UserManager.Permissions permission,
            @Mixin
            OptionsMixin options
    ) {
        def userManager = createApplicationContext(options).getBean(UserManager)
        userManager.grantPermission(username, permission)
    }

    @Command(name = "enable-prometheus-authentication", description = "Enables authentication for the prometheus endpoint.", mixinStandardHelpOptions = true)
    void enablePrometheusAuthentication(
            @Mixin
            OptionsMixin options
    ) {
        def prometheusConfigurator = createApplicationContext(options).getBean(PrometheusConfigurator)
        prometheusConfigurator.enableAuthentication()
    }

    @Command(name = "set-global-property", description = "Create a global property.", mixinStandardHelpOptions = true)
    void setGlobalProperty(
            @Parameters(paramLabel = "key", description = "The property's key")
            String key,
            @Parameters(paramLabel = "value", description = "The property's value")
            String value,
            @Mixin
            OptionsMixin options
    ) {
        def globalPropertyManager = createApplicationContext(options).getBean(GlobalPropertyManager)
        globalPropertyManager.setGlobalProperty(key, value)
    }


    @Command(name = "create-credential", description = "Create a credential withing a job.", mixinStandardHelpOptions = true)
    void createCredential(
            @Parameters(paramLabel = "jobName", description = "The job to create the credential for")
            String jobName,
            @Parameters(paramLabel = "id", description = "Credential's id")
            String id,
            @Parameters(paramLabel = "username", description = "Associated username")
            String username,
            @Parameters(paramLabel = "password", description = "Associated password")
            String password,
            @Parameters(paramLabel = "description", description = "The description")
            String description,
            @Mixin
            OptionsMixin options
    ) {
        def globalPropertyManager = createApplicationContext(options).getBean(JobManager)
        globalPropertyManager.createCredential(jobName, id, username, password, description)
    }

    private ApplicationContext createApplicationContext(OptionsMixin options) {
        ApplicationContext.run()
                .registerSingleton(new JenkinsConfiguration(options.jenkinsUrl, options.jenkinsUsername, options.jenkinsPassword, options.insecure))
    }

    static class OptionsMixin {
        // args group jenkins
        @Option(names = ['--jenkins-url'], required = true, description = 'The url of your external jenkins')
        public String jenkinsUrl
        @Option(names = ['--jenkins-username'], required = true, description = 'Mandatory when --jenkins-url is set')
        public String jenkinsUsername
        @Option(names = ['--jenkins-password'], required = true, description = 'Mandatory when --jenkins-url is set')
        public String jenkinsPassword
        @Option(names = ['--insecure'], description = 'Sets insecure-mode for the HTTP client which skips cert validation')
        public boolean insecure
    }
}
