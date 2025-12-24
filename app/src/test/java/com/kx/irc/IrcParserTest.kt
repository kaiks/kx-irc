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
}
