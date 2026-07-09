package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Test

class IrcConfigTest {
    @Test
    fun authPasswordUsesConfiguredServerPassword() {
        val config = IrcConfig(
            serverPassword = "fallback"
        )

        assertEquals("fallback", config.toAuthPassword())
    }

    @Test
    fun defaultConfigurationUsesTlsForDefaultTlsPort() {
        val config = IrcConfig()
        assertEquals(true, config.useTls)
    }

    @Test
    fun channelListParsesCommaAndSpaceSeparated() {
        val config = IrcConfig(channels = "#one, #two #three")
        assertEquals(listOf("#one", "#two", "#three"), config.channelList())
    }

    @Test
    fun validConnectionDoesNotRequirePassword() {
        val config = IrcConfig(host = "example", port = 6667)
        assertEquals(null, config.validate())
    }

    @Test
    fun validateRejectsOutOfRangePortAndLineBreaks() {
        assertEquals(
            "Port must be between 1 and 65535",
            IrcConfig(host = "example", port = 65536).validate()
        )
        assertEquals(
            "Password cannot contain line breaks",
            IrcConfig(host = "example", serverPassword = "secret\nOPER root").validate()
        )
    }
}
