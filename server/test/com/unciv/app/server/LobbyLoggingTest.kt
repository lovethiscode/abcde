package com.unciv.app.server

import org.junit.Test
import org.junit.Assert.*
import org.slf4j.LoggerFactory

class LobbyLoggingTest {
    @Test fun productionLoggingSuppressesDebugButKeepsWarnings() {
        for (name in listOf("ROOT", "org.mongodb.driver.protocol.command", "org.mongodb.driver.connection", "io.netty")) {
            val logger = LoggerFactory.getLogger(name)
            assertFalse("$name must not emit DEBUG logs",logger.isDebugEnabled)
            assertTrue("$name must retain warnings",logger.isWarnEnabled)
        }
    }
}
