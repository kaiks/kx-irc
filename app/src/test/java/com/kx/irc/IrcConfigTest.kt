package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Test

class IrcConfigTest {
    @Test
    fun zncPasswordOverridesServerPassword() {
        val config = IrcConfig(
            serverPassword = "fallback"
        )

        assertEquals("fallback", config.toAuthPassword())
    }

    @Test
    fun serverPasswordUsedWhenZncEmpty() {
        val config = IrcConfig(serverPassword = "serverpass")
        assertEquals("serverpass", config.toAuthPassword())
    }

    @Test
    fun channelListParsesCommaAndSpaceSeparated() {
        val config = IrcConfig(channels = "#one, #two #three")
        assertEquals(listOf("#one", "#two", "#three"), config.channelList())
    }

    @Test
    fun validateRequiresPasswordForZnc() {
        val config = IrcConfig(host = "example", port = 6667)
        assertEquals(null, config.validate())
    }
}
