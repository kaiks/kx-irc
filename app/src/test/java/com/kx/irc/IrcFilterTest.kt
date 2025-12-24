package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class IrcFilterTest {
    @Test
    fun filterMessagesByTargetReturnsAllWhenAllSelected() {
        val messages = listOf(
            IrcMessage(1, Instant.EPOCH, "alice", "#one", "hi"),
            IrcMessage(2, Instant.EPOCH, "bob", "#two", "yo")
        )

        assertEquals(messages, filterMessagesByTarget(messages, "*"))
    }

    @Test
    fun filterMessagesByTargetFiltersByChannel() {
        val messages = listOf(
            IrcMessage(1, Instant.EPOCH, "alice", "#one", "hi"),
            IrcMessage(2, Instant.EPOCH, "bob", "#two", "yo")
        )

        assertEquals(listOf(messages[0]), filterMessagesByTarget(messages, "#one"))
    }
}
