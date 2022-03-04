package com.cloudogu.gop.application

import ch.qos.logback.classic.Level
import com.cloudogu.gop.application.modules.ModuleRepository
import com.cloudogu.gop.utils.TestLogger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify

class GopApplicationTest {

    private GopApplication gop
    private ModuleRepository moduleRepository
    private TestLogger testLogger

    @BeforeEach
    void setup() {
        moduleRepository = mock(ModuleRepository.class)
        gop = new GopApplication(moduleRepository)
        testLogger = new TestLogger(gop.getClass())
    }

    @Test
    void testSucessfulGopInstallation() {
        gop.start()

        verify(moduleRepository).execute()
        assertEquals(testLogger.getLogs().size, 2)
        assertTrue(testLogger.getLogs().contains("Starting Gop Application", Level.INFO))
        assertTrue(testLogger.getLogs().contains("Gop Application installed", Level.INFO))
    }
}