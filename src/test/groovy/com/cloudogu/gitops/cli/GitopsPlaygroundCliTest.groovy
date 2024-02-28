package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.K8sClientForTest
import com.github.stefanbirkner.systemlambda.SystemLambda
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

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
    void 'Returns error, when applying is not confirmed'() {
        int status = SystemLambda.catchSystemExit(() -> {
            writeViaSystemIn('something')
            new GitopsPlaygroundCliForTest().run()
        })

        assertThat(status).isNotZero()
    }

    @Test
    void 'Runs when applying is confirmed'() {
        writeViaSystemIn('y')
        new GitopsPlaygroundCliForTest().run()

        verify(application).start()
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS) // Avoids blocking if input is read by error
    void 'Runs without confirmation when yes parameter is set'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.pipeYes = true
        cli.run()

        verify(application).start()
    }

    @Test
    void 'Returns error, when destroying is not confirmed'() {
        int status = SystemLambda.catchSystemExit(() -> {
            writeViaSystemIn('something')
            def cli = new GitopsPlaygroundCliForTest()
            cli.destroy = true
            cli.run()
        })

        assertThat(status).isNotZero()
    }

    @Test
    void 'Destroys when confirmed'() {
        writeViaSystemIn('y')
        def cli = new GitopsPlaygroundCliForTest()
        cli.destroy = true
        cli.run()

        verify(destroyer).destroy()
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    // Avoids blocking if input is read by error
    void 'Destroys without confirmation when yes parameter is set'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.pipeYes = true
        cli.destroy = true
        cli.run()

        verify(destroyer).destroy()
    }

    @Test
    void 'sets simplified logging pattern'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.pipeYes = true
        cli.run()

        assertThat(getLoggingPattern()).doesNotContain('%logger', '%thread')
    }

    @Test
    void 'keeps simplified logging pattern when trace is enabled'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.pipeYes = true
        cli.trace = true
        cli.run()

        assertThat(getLoggingPattern()).contains('%logger', '%thread')
    }
    
    @Test
    void 'keeps simplified logging pattern when debug is enabled'() {
        def cli = new GitopsPlaygroundCliForTest()
        cli.pipeYes = true
        cli.debug = true
        cli.run()

        assertThat(getLoggingPattern()).contains('%logger', '%thread')
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

        @Override
        protected ApplicationContext createApplicationContext() {
            when(applicationContext.getBean(K8sClient)).thenReturn(k8sClient)
            when(applicationContext.registerSingleton(any())).thenReturn(applicationContext)
            when(applicationContext.getBean(Application)).thenReturn(application)
            when(applicationContext.getBean(ApplicationConfigurator)).thenReturn(applicationConfigurator)
            when(applicationContext.getBean(Destroyer)).thenReturn(destroyer)
            return applicationContext
        }
    }
}
