package com.cloudogu.gitops.core

import ch.qos.logback.classic.Level
import com.cloudogu.gitops.core.modules.ModuleRepository
import com.cloudogu.gitops.utils.TestLogger
import com.github.stefanbirkner.systemlambda.SystemLambda
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.mockito.Mockito.*

class ApplicationTest {

    private Application application
    private ModuleRepository moduleRepository
    private TestLogger testLogger

    @BeforeEach
    void setup() {
        moduleRepository = mock(ModuleRepository.class)
        application = new Application(moduleRepository)
        testLogger = new TestLogger(application.getClass())
    }

    @Test
    void 'application runs successfully'() {
        application.start()

        verify(moduleRepository).execute()
        assertEquals(testLogger.getLogs().size, 2)
        assertTrue(testLogger.getLogs().contains("Starting Application", Level.INFO))
        assertTrue(testLogger.getLogs().contains("Application finished", Level.INFO))
    }
    
    @Test
    void 'application returns exit code 1 on exception'() {
        when(moduleRepository.execute())
                .thenThrow(new RuntimeException("Mocked"));

        int status = SystemLambda.catchSystemExit(() -> {
            application.start()
        });

        assertEquals(1, status);

        assertTrue(testLogger.getLogs().contains("Application failed: Mocked", Level.ERROR))
    }
}