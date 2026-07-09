package com.kx.irc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrcViewModelTargetSelectionTest {
    @Test
    fun keepsPreviousNonServerTargetAfterReconnect() {
        val selected = pickTargetAfterConnect(
            previousTarget = "#android",
            configuredChannels = listOf("#general", "#random"),
            knownChannelTargets = listOf("#general", "#random"),
            knownPrivateTargets = listOf("alice")
        )

        assertEquals("#android", selected)
    }

    @Test
    fun picksFirstConfiguredChannelWhenPreviousTargetIsServer() {
        val selected = pickTargetAfterConnect(
            previousTarget = "server",
            configuredChannels = listOf("#general", "#random"),
            knownChannelTargets = listOf("#random"),
            knownPrivateTargets = listOf("alice")
        )

        assertEquals("#general", selected)
    }

    @Test
    fun fallsBackToKnownTargetsThenServer() {
        val fromKnownChannel = pickTargetAfterConnect(
            previousTarget = "server",
            configuredChannels = emptyList(),
            knownChannelTargets = listOf("#general"),
            knownPrivateTargets = listOf("alice")
        )
        assertEquals("#general", fromKnownChannel)

        val fromKnownPrivate = pickTargetAfterConnect(
            previousTarget = "server",
            configuredChannels = emptyList(),
            knownChannelTargets = emptyList(),
            knownPrivateTargets = listOf("alice")
        )
        assertEquals("alice", fromKnownPrivate)

        val server = pickTargetAfterConnect(
            previousTarget = "server",
            configuredChannels = emptyList(),
            knownChannelTargets = emptyList(),
            knownPrivateTargets = emptyList()
        )
        assertEquals("server", server)
    }

    @Test
    fun foregroundReconnectOnlyStartsForDisconnectedOrFailedValidConfigurations() {
        val validConfig = IrcConfig(host = "irc.example.com", port = 6697)
        assertTrue(
            shouldReconnectOnForeground(
                status = ConnectionStatus.Disconnected,
                config = validConfig
            )
        )

        assertFalse(
            shouldReconnectOnForeground(
                status = ConnectionStatus.Connecting,
                config = validConfig
            )
        )
        assertTrue(
            shouldReconnectOnForeground(
                status = ConnectionStatus.Failed("Socket closed"),
                config = validConfig
            )
        )
        assertFalse(
            shouldReconnectOnForeground(
                status = ConnectionStatus.Connected("irc.example.com:6697"),
                config = validConfig
            )
        )
        assertFalse(
            shouldReconnectOnForeground(
                status = ConnectionStatus.Disconnected,
                config = IrcConfig(host = "", port = 6697)
            )
        )
    }
}
