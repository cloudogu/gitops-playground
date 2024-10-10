package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.schema.Schema
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.utils.K8sClientForTest
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*
// Avoids blocking if input is read by error
@Timeout(value = 2, unit = TimeUnit.SECONDS)
class GitopsPlaygroundCliTest {

    static final String ORIGINAL_LOGGING_PATTERN = loggingEncoder.pattern
    
    K8sClientForTest k8sClient = new K8sClientForTest(new HashMap())
    Application application = mock(Application)
    ApplicationConfigurator applicationConfigurator = mock(ApplicationConfigurator)
    Destroyer destroyer = mock(Destroyer)
    
    @AfterEach
    void setup() {
        // Restore logging pattern, if modified
        loggingEncoder.setPattern(ORIGINAL_LOGGING_PATTERN)
    }

    @Test
    void 'Starts regularly'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--yes')

        verify(applicationConfigurator).initAndValidateConfig(any(Map))
        verify(application).start()
    }
    
    @Test
    void 'Outputs config file'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--output-config-file')

        verify(applicationConfigurator).initAndValidateConfig(any(Map))
        verify(application, never()).start()
    }

    
    @Test
    void 'Returns error, when applying is not confirmed'() {
        def cli = new GitopsPlaygroundCliForTest()
        writeViaSystemIn('something')
        def status = cli.run()

        assertThat(status).isEqualTo(ReturnCode.NOT_CONFIRMED)
    }

    @Test
    void 'Runs when applying is confirmed'() {
        def cli = new GitopsPlaygroundCliForTest()
        writeViaSystemIn('y')
        
        cli.run()

        verify(application).start()
    }

    @Test
    void 'Runs without confirmation when yes parameter is set'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--yes')

        verify(application).start()
    }

    @Test
    void 'Returns error, when destroying is not confirmed'() {
        def cli = new GitopsPlaygroundCliForTest()
        
        writeViaSystemIn('something')
        
        def status = cli.run('--destroy')

        assertThat(status).isEqualTo(ReturnCode.NOT_CONFIRMED)
    }

    @Test
    void 'Destroys when confirmed'() {
        def cli = new GitopsPlaygroundCliForTest()
        
        writeViaSystemIn('y')
        
        cli.run '--destroy'

        verify(destroyer).destroy()
        verify(application, never()).start()
    }

    @Test
    void 'Destroys without confirmation when yes parameter is set'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--destroy', '--yes')

        verify(destroyer).destroy()
    }

    @Test
    void 'sets simplified logging pattern'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--yes')

        assertThat(getLoggingPattern()).doesNotContain('%logger', '%thread')
    }

    @Test
    void 'keeps simplified logging pattern when trace is enabled'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--trace','--yes')

        assertThat(getLoggingPattern()).contains('%logger', '%thread')
    }
    
    @Test
    void 'keeps simplified logging pattern when debug is enabled'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.run('--debug', '--yes')

        assertThat(getLoggingPattern()).contains('%logger', '%thread')
    }

    @Test
    void 'cli overwrites config file'() {
        /* TODO
        def configFile = File.createTempFile("gitops-playground", '.yaml')
        configFile.deleteOnExit()
        configFile.text = """
images:
  kubectl: "localhost:30000/kubectl"
  helm: "localhost:30000/helm"
        """

        applicationConfigurator.setConfig(almostEmptyConfig)
        applicationConfigurator
                .setConfig(configFile)
        def config = applicationConfigurator
                .setConfig([
                        images: [
                                kubectl: null, // do not overwrite default value
                                helm   : "localhost:30000/cli/helm",
                        ]
                ])

        assertThat(config['images']['kubectl']).isEqualTo('localhost:30000/kubectl')
        assertThat(config['images']['helm']).isEqualTo('localhost:30000/cli/helm')
        */
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
    
    class GitopsPlaygroundCliForTest extends GitopsPlaygroundCli {
        ApplicationContext applicationContext = mock(ApplicationContext)

        GitopsPlaygroundCliForTest() {
            super(GitopsPlaygroundCliTest.this.k8sClient, GitopsPlaygroundCliTest.this.applicationConfigurator)

            when(applicationConfigurator.initAndValidateConfig(any(Map))).thenAnswer(new Answer<Schema>() {
                @Override
                Schema answer(InvocationOnMock invocation) throws Throwable {
                    invocation.getArgument(0)
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
