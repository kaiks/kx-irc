package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IrcFormattingTest {
    @Test
    fun zncTimestampIsUsedAndRemovedFromMessageBody() {
        val message = IrcMessage(
            id = 1,
            timestamp = Instant.EPOCH,
            sender = "alice",
            target = "#android",
            body = "[12:34:56] \u0002hello\u0002"
        )

        val formatted = formatMessageLine(message)

        assertEquals("12:34:56 (alice) hello", formatted.text)
        assertTrue(formatted.spanStyles.isNotEmpty())
    }

    @Test
    fun unrelatedBracketedTextIsNotRemoved() {
        assertEquals(Pair(null, "[tomorrow] hello"), extractZncTimestamp("[tomorrow] hello"))
    }

    @Test
    fun messageLineAnnotatesNickAndLinks() {
        val formatted = formatMessageLine(
            IrcMessage(1, Instant.EPOCH, "alice", "#android", "See https://example.com/docs.")
        )

        assertEquals("alice", formatted.getStringAnnotations(NICK_ANNOTATION, 11, 11).single().item)
        assertEquals(
            "https://example.com/docs",
            formatted.getStringAnnotations(LINK_ANNOTATION, formatted.text.indexOf("https"), formatted.text.indexOf("https")).single().item
        )
    }
}
