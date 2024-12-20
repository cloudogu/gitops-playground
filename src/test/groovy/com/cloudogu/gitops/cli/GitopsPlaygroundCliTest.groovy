package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.K8sClient
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

// Avoids blocking if input is read by error
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GitopsPlaygroundCliTest {

    static final String ORIGINAL_LOGGING_PATTERN = loggingEncoder.pattern

    K8sClient k8sClient = mock(K8sClient)
    Application application = mock(Application)
    ApplicationConfigurator applicationConfigurator = mock(ApplicationConfigurator)
    Destroyer destroyer = mock(Destroyer)
    GitopsPlaygroundCliForTest cli = new GitopsPlaygroundCliForTest()
    static YAMLMapper yamlMapper = new YAMLMapper()
    
    @AfterEach
    void setup() {
        // Restore logging pattern, if modified
        loggingEncoder.setPattern(ORIGINAL_LOGGING_PATTERN)
    }

    @Test
    void 'Starts regularly'() {
        def status = cli.run('--yes')

        assertThat(status).isEqualTo(ReturnCode.SUCCESS)
        verify(applicationConfigurator).initAndValidateConfig(any(Config))
        verify(application).start()
    }

    @Test
    void 'Starts with config file'() {
        String pathToConfigFile = "./src/test/resources/testMainConfig.yaml"

        assertThat(new File(pathToConfigFile).isFile()).withFailMessage("config file for test do not exists anymore.").isTrue()

        def status = cli.run('--config-file=' + pathToConfigFile)
        assertThat(status).isEqualTo(ReturnCode.SUCCESS)

        // Verify the first interaction
        verify(applicationConfigurator).initAndValidateConfig(any(Config))

        // Check application starts
        verify(application).start()
    }
    @Test
    void 'fails on starts because config file with helm overrides, not allowed'() {
        String pathToConfigFile = "./src/test/resources/errorConfig.yaml"

        assertThat(new File(pathToConfigFile).isFile()).withFailMessage("config file for test do not exists anymore.").isTrue()

        def exception = shouldFail(RuntimeException) {
            cli.run('--config-file=' + pathToConfigFile)
        }
        assertThat(exception.message).contains('Config file invalid')

    }
    
    @Test
    void 'Starts with config map'() {
        when(k8sClient.getConfigMap('my-config', 'config.yaml')).thenReturn('{"application": {"yes": true}}')

        def status = cli.run("--config-map=my-config")

        assertThat(status).isEqualTo(ReturnCode.SUCCESS)
        // ensure init is called with Config
        verify(applicationConfigurator).initAndValidateConfig(any(Config))
        verify(application).start()
    }

    @Test
    void 'Outputs config file'() {
        def status = cli.run('--output-config-file')

        assertThat(status).isEqualTo(ReturnCode.SUCCESS)
        verify(applicationConfigurator).initAndValidateConfig(any(Config))
        verify(application, never()).start()
    }
    
    @Test
    void 'Outputs version'() {
        def cli = new GitopsPlaygroundCliForTest()
        def status = cli.run('--version')

        assertThat(status).isEqualTo(ReturnCode.SUCCESS)
        verify(applicationConfigurator).initAndValidateConfig(any(Config))
        verify(application, never()).start()
    }

    @Test
    void 'Outputs help'() {
        def cli = new GitopsPlaygroundCliForTest()
        def status = cli.run('--help')

        assertThat(status).isEqualTo(ReturnCode.SUCCESS)
        verify(applicationConfigurator).initAndValidateConfig(any(Config))
        verify(application, never()).start()
    }

    @Test
    void 'Returns error, when applying is not confirmed'() {
        writeViaSystemIn('something')
        def status = cli.run()

        assertThat(status).isEqualTo(ReturnCode.NOT_CONFIRMED)
    }

    @Test
    void 'Runs when applying is confirmed'() {
        writeViaSystemIn('y')
        
        cli.run()

        verify(application).start()
    }

    @Test
    void 'Runs without confirmation when yes parameter is set'() {
        cli.run('--yes')

        verify(application).start()
    }

    @Test
    void 'Returns error, when destroying is not confirmed'() {
        
        writeViaSystemIn('something')
        
        def status = cli.run('--destroy')

        assertThat(status).isEqualTo(ReturnCode.NOT_CONFIRMED)
    }

    @Test
    void 'Destroys when confirmed'() {
        
        writeViaSystemIn('y')
        
        cli.run '--destroy'

        verify(destroyer).destroy()
        verify(application, never()).start()
    }

    @Test
    void 'Destroys without confirmation when yes parameter is set'() {
        cli.run('--destroy', '--yes')

        verify(destroyer).destroy()
    }

    @Test
    void 'sets simplified logging pattern'() {
        cli.run('--yes')

        assertThat(getLoggingPattern()).doesNotContain('%logger', '%thread')
    }

    @Test
    void 'keeps simplified logging pattern when trace is enabled'() {
        cli.run('--trace','--yes')

        assertThat(getLoggingPattern()).contains('%logger', '%thread')
    }
    
    @Test
    void 'keeps simplified logging pattern when debug is enabled'() {
        cli.run('--debug', '--yes')

        assertThat(getLoggingPattern()).contains('%logger', '%thread')
    }

    @Test
    void 'fails on invalid config file'() {
        
        def configFile = File.createTempFile("gop", '.yaml')
        configFile.deleteOnExit()
        configFile.text = 'something: not-matching-our-schema'
        
        def exception = shouldFail(RuntimeException) {
            cli.run("--config-file=${configFile}", '--yes' )
        }
        assertThat(exception.message).contains('Config file invalid')
    }

    @Test
    void 'fails on invalid config map'() {
        when(k8sClient.getConfigMap('my-config', 'config.yaml')).thenReturn('something: not-matching-our-schema')

        def exception = shouldFail(RuntimeException) {
            cli.run('--config-map=my-config', '--yes' )
        }
        assertThat(exception.message).contains('Config file invalid')
    }
    
    @Test
    void 'Precedence: config file overwrite confiMap, cli overwrites config file'() {

        def cmConfig = [
                application: [
                        username: 'cmUser', password: 'cmPw', namePrefix: 'cmPref'
                ]
        ] 
        def fileConfig = [
                application: [
                        username: 'fileUser', password: 'filePw'
                ]
        ]
        
        def configFile = File.createTempFile("gop", '.yaml')
        configFile.deleteOnExit()
        
        configFile.text = toYaml(fileConfig)
        when(k8sClient.getConfigMap('my-config', 'config.yaml')).thenReturn(toYaml(cmConfig))

        cli.run("--config-file=${configFile}", '--config-map=my-config', '--username=paramUser', '--yes')
        
        assertThat(cli.lastSchema.application.username).isEqualTo('paramUser')
        assertThat(cli.lastSchema.application.password).isEqualTo('filePw')
        assertThat(cli.lastSchema.application.namePrefix).isEqualTo('cmPref')
    }


    static String getLoggingPattern() {
        loggingEncoder.pattern
    }

    static PatternLayoutEncoder getLoggingEncoder() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()
        def rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        def consoleAppender = rootLogger.getAppender('STDOUT') as ConsoleAppender
        consoleAppender.getEncoder() as PatternLayoutEncoder
    }

    void writeViaSystemIn(String value) {
        ByteArrayInputStream inContent = new ByteArrayInputStream("${value}\n".getBytes())
        System.setIn(inContent)
    }
    
    static String toYaml(Map map) {
        yamlMapper.writeValueAsString(map)
    }
    
    class GitopsPlaygroundCliForTest extends GitopsPlaygroundCli {
        ApplicationContext applicationContext = mock(ApplicationContext)
        Config lastSchema = null

        GitopsPlaygroundCliForTest() {
            super(GitopsPlaygroundCliTest.this.k8sClient, GitopsPlaygroundCliTest.this.applicationConfigurator)

            when(applicationConfigurator.initAndValidateConfig(any(Config))).thenAnswer(new Answer<Config>() {
                @Override
                Config answer(InvocationOnMock invocation) throws Throwable {
                    lastSchema = invocation.getArgument(0)
                    return lastSchema
                }
            })

        }
        
        @Override
        protected ApplicationContext createApplicationContext() {
            when(applicationContext.getBean(Application)).thenReturn(application)
            when(applicationContext.getBean(Destroyer)).thenReturn(destroyer)
            
            return applicationContext
        }
    }
}
