package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Test

class IrcParserTest {
    @Test
    fun parseIrcLineWithPrefixAndTrailing() {
        val line = ":nick!user@host PRIVMSG #chan :hello there"
        val parsed = parseIrcLine(line)

        assertEquals("nick!user@host", parsed.prefix)
        assertEquals("PRIVMSG", parsed.command)
        assertEquals(listOf("#chan"), parsed.params)
        assertEquals("hello there", parsed.trailing)
    }

    @Test
    fun parseNickExtractsNick() {
        assertEquals("nick", parseNick("nick!user@host"))
        assertEquals("server", parseNick("server"))
        assertEquals("", parseNick(null))
    }

    @Test
    fun parseIrcLineWithTags() {
        val line = "@time=2025-12-24T22:17:06Z :nick!u@h PRIVMSG #chan :hello"
        val parsed = parseIrcLine(line)

        assertEquals("2025-12-24T22:17:06Z", parsed.tags["time"])
        assertEquals("PRIVMSG", parsed.command)
        assertEquals(listOf("#chan"), parsed.params)
        assertEquals("hello", parsed.trailing)
    }
}
