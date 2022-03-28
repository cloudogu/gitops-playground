package com.cloudogu.gitops.core

import ch.qos.logback.classic.Level
import com.cloudogu.gitops.core.modules.ModuleRepository
import com.cloudogu.gitops.utils.TestLogger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify

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
}