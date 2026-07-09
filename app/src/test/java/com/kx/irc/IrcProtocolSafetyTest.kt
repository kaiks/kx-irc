package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcProtocolSafetyTest {
    @Test
    fun longUnicodeMessageIsSplitIntoValidIrcLines() {
        val commands = splitPrivmsgCommands("#android", "🙂 ".repeat(300))

        assertTrue(commands.size > 1)
        assertTrue(commands.all(::isSafeIrcLine))
        assertEquals("🙂 ".repeat(300), commands.joinToString("") { it.substringAfter("PRIVMSG #android :") })
    }

    @Test
    fun messageAndTargetInjectionAreRejected() {
        assertFalse(isValidIrcTarget("#room\r\nOPER root"))
        assertFalse(isSafeIrcLine("PRIVMSG #room :hello\r\nOPER root"))
        assertTrue(runCatching { splitPrivmsgCommands("#room", "hello\nworld") }.isFailure)
    }

    @Test
    fun caseFoldingHonorsIrcMappings() {
        assertTrue(ircEquals("#KX[IRC]", "#kx{irc}", IrcCaseMapping.RFC1459))
        assertFalse(ircEquals("#KX[IRC]", "#kx{irc}", IrcCaseMapping.ASCII))
        assertTrue(ircEquals("NICK^", "nick~", IrcCaseMapping.RFC1459))
        assertFalse(ircEquals("NICK^", "nick~", IrcCaseMapping.STRICT_RFC1459))
    }
}
