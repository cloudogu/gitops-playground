package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.schema.JsonSchemaValidator
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import picocli.CommandLine

import static com.cloudogu.gitops.config.ConfigConstants.APP_NAME
import static com.cloudogu.gitops.utils.MapUtils.deepMerge 
/**
 * Provides the entrypoint to the application as well as all config parameters.
 * When changing parameters, make sure to update the Config for the config file as well
 *
 * @see Config
 */
@Slf4j
class GitopsPlaygroundCli {

    K8sClient k8sClient
    ApplicationConfigurator applicationConfigurator

    GitopsPlaygroundCli(K8sClient k8sClient = new K8sClient(new CommandExecutor(), new FileSystemUtils(), null),
                        ApplicationConfigurator applicationConfigurator = new ApplicationConfigurator()) {
        this.k8sClient = k8sClient
        this.applicationConfigurator = applicationConfigurator
    }

    ReturnCode run(String[] args) {
        setLogging(args)

        log.debug("Reading initial CLI params")
        def cliParams = new Config()
        new CommandLine(cliParams).parseArgs(args)

        if (cliParams.application.usageHelpRequested) {
            // if help is requested picocli help is used and printed by execute automatically
            new CommandLine(cliParams).execute(args)
            return ReturnCode.SUCCESS
        }
        
        def version = createVersionOutput()
        if (cliParams.application.versionInfoRequested) {
            println version
            return ReturnCode.SUCCESS
        }

        def config = readConfigs(args)
        if (config.application.outputConfigFile) {
            println(config.toYaml(false))
            return ReturnCode.SUCCESS
        }
        
        // Set internal values in config after help/version/output because these should work without connecting to k8s
        // eg a simple docker run .. --help should not fail with connection refused
        config = applicationConfigurator.initConfig(config)
        log.debug("Actual config: ${config.toYaml(true)}")

        def context = createApplicationContext()
        register(config, context)

        if (config.application.destroy) {
            log.info version
            if (!confirm("Destroying gitops playground in kubernetes cluster '${k8sClient.currentContext}'.", config)) {
                return ReturnCode.NOT_CONFIRMED
            }

            Destroyer destroyer = context.getBean(Destroyer)
            destroyer.destroy()
        } else {
            log.info version
            if (!confirm("Applying gitops playground to kubernetes cluster '${k8sClient.currentContext}'.", config)) {
                return ReturnCode.NOT_CONFIRMED
            }
            Application app = context.getBean(Application)
            app.start()

            printWelcomeScreen()
        }

        return ReturnCode.SUCCESS
    }

    protected String createVersionOutput() {
        def versionName = Version.NAME.replace('\\n', '\n')

        if (versionName.trim().startsWith('(')) {
            // When there is no git tag, print commit without parentheses
            versionName = versionName.trim()
                    .replace('(', '')
                    .replace(')', '')
        }
        return "${APP_NAME} ${versionName}"
    }

    /** Can be used as a hook by child classes */
    @SuppressWarnings('GrMethodMayBeStatic')
    // static methods cannot be overridden
    protected void register(Config config, ApplicationContext context) {
        context.registerSingleton(config)
    }

    private static boolean confirm(String message, Config config) {
        if (config.application.yes) {
            return true
        }

        log.info("\n${message}\nContinue? y/n [n]")

        def input = System.in.newReader().readLine()

        return input == 'y'
    }

    /** Can be used as a hook by tests */
    protected ApplicationContext createApplicationContext() {
        ApplicationContext.run()
    }

    private void setLogging(String[] args) {
        Logger logger = (Logger) LoggerFactory.getLogger("com.cloudogu.gitops")
        if (args.contains('--trace') || args.contains('-x')) {
            log.info("Setting loglevel to trace")
            logger.setLevel(Level.TRACE)
            // log levels can be set via picocli.trace sys env - defaults to 'WARN'
            System.setProperty("picocli.trace", "DEBUG")
        } else if (args.contains('--debug') || args.contains('-d')) {
            System.setProperty("picocli.trace", "INFO")
            logger.setLevel(Level.DEBUG)
            log.info("Setting loglevel to debug")
        } else {
            setSimpleLogPattern()
        }
    }

    /**
     * Changes log pattern to a simpler one, to reduce noise for normal users
     */
    void setSimpleLogPattern() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()
        def rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        def defaultPattern = ((rootLogger.getAppender('STDOUT') as ConsoleAppender)
                .getEncoder() as PatternLayoutEncoder).pattern

        // Avoid duplicate output by existing appender
        rootLogger.detachAppender('STDOUT')
        PatternLayoutEncoder encoder = new PatternLayoutEncoder()
        // Remove less relevant details from log pattern
        encoder.setPattern(defaultPattern
                .replaceAll(" \\S*%thread\\S* ", " ")
                .replaceAll(" \\S*%logger\\S* ", " "))
        encoder.setContext(loggerContext)
        encoder.start()
        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>()
        appender.setName('STDOUT')
        appender.setContext(loggerContext)
        appender.setEncoder(encoder)
        appender.start()
        rootLogger.addAppender(appender)
    }

    private Config readConfigs(String[] args) {
        def cliParams = new Config()
        new CommandLine(cliParams).parseArgs(args)

        String configFilePath = cliParams.application.configFile
        String configMapName = cliParams.application.configMap

        Map configFile = [:]
        Map configMap = [:]

        if (configFilePath) {
            log.debug("Reading config file ${configFilePath}")
            configFile = validateConfig(new File(configFilePath).text)
        }

        if (configMapName) {
            log.debug("Reading config map ${configMapName}")
            def configValues = k8sClient.getConfigMap(configMapName, 'config.yaml')
            configMap = validateConfig(configValues)
        }

        // Last one takes precedence
        def configPrecedence = [configMap, configFile]
        Map mergedConfigs = [:]
        configPrecedence.each {
            deepMerge(it, mergedConfigs)
        }

        // DeepMerge with default Config values to keep the default values defined in Config.groovy
        mergedConfigs = deepMerge(mergedConfigs,new Config().toMap())

        log.debug("Writing CLI params into config")
        Config mergedConfig = Config.fromMap(mergedConfigs)
        new CommandLine(mergedConfig).parseArgs(args)

        applicationConfigurator.validateConfig(mergedConfig)
        
        return mergedConfig
    }

    private static Map validateConfig(String configValues) {
        def map = new YamlSlurper().parseText(configValues)
        if (!(map instanceof Map)) {
            throw new RuntimeException("Could not parse YAML as map: $map")
        }
        JsonSchemaValidator.validate(map as Map)
        return map as Map
    }

    void printWelcomeScreen() {
        log.info '''\n
  |----------------------------------------------------------------------------------------------|
  |                       Welcome to the GitOps playground by Cloudogu!
  |----------------------------------------------------------------------------------------------|
  |
  | Please find the URLs of the individual applications in our README:
  | https://github.com/cloudogu/gitops-playground/blob/main/README.md#table-of-contents
  |
  | A good starting point might also be the services or ingresses inside your cluster:  
  | kubectl get svc -A
  | Or (depending on your config)
  | kubectl get ing -A
  |
  | Please be aware, Jenkins and Argo CD may take some time to build and deploy all apps.
  |----------------------------------------------------------------------------------------------|
'''
    }
}
