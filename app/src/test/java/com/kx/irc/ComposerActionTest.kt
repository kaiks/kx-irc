package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerActionTest {
    @Test
    fun parsesSupportedCommands() {
        assertEquals(ComposerAction.Join("#android"), parseComposerAction("/join #android", "server"))
        assertEquals(ComposerAction.Part("#android", "bye"), parseComposerAction("/part bye", "#android"))
        assertEquals(ComposerAction.Action("#android", "waves"), parseComposerAction("/me waves", "#android"))
        assertEquals(ComposerAction.Message("alice", "hello"), parseComposerAction("/msg alice hello", "#android"))
        assertEquals(ComposerAction.Nick("newNick"), parseComposerAction("/nick newNick", "#android"))
    }

    @Test
    fun rejectsMessagesWithoutConversationAndUnknownCommands() {
        assertTrue(parseComposerAction("hello", "server") is ComposerAction.Error)
        assertTrue(parseComposerAction("/wat", "#android") is ComposerAction.Error)
        assertTrue(parseComposerAction("/join android", "server") is ComposerAction.Error)
    }

    @Test
    fun detectsWholeNickMentionsUsingIrcCaseMapping() {
        assertTrue(isIrcMention("hello KX[User]!", "kx{user}"))
        assertFalse(isIrcMention("kx{users}", "kx{user}"))
        assertFalse(isIrcMention("hello there", "kx{user}"))
    }

    @Test
    fun nameReplyAndTypedPrefixProduceOptionalMentionSuggestions() {
        val names = parseChannelNames("@Alice +albert bob")

        assertEquals(listOf("albert", "Alice"), findMentionSuggestions("hello al", names, ownNick = "bob"))
        assertEquals("hello Alice ", insertMentionSuggestion("hello al", "Alice"))
        assertTrue(findMentionSuggestions("hello a", names, ownNick = "bob").isEmpty())
    }

    @Test
    fun channelNamesPreserveOperatorAndVoicePrivileges() {
        val members = parseChannelMembers("~Owner @Alice +bob plain")

        assertEquals("~", members[0].displayPrefix)
        assertTrue(members[0].isOperator)
        assertTrue(members[1].isOperator)
        assertTrue(members[2].isVoiced)
        assertFalse(members[3].isVoiced)
    }

    @Test
    fun channelMemberModesUpdateRosterPrivileges() {
        val members = parseChannelMembers("@Alice bob").toMutableList()

        assertTrue(applyChannelMemberModes(members, "+vo", listOf("bob", "bob")))
        assertTrue(members.single { it.nick == "bob" }.isOperator)
        assertTrue(applyChannelMemberModes(members, "-o", listOf("Alice")))
        assertFalse(members.single { it.nick == "Alice" }.isOperator)
    }

    @Test
    fun sentMessageRevealPolicyKeepsReadingPositionUnlessComposerStartedAtBottom() {
        assertTrue(shouldRevealSentMessage(currentlyAtBottom = true, wasAtBottomWhenTypingStarted = false))
        assertTrue(shouldRevealSentMessage(currentlyAtBottom = false, wasAtBottomWhenTypingStarted = true))
        assertFalse(shouldRevealSentMessage(currentlyAtBottom = false, wasAtBottomWhenTypingStarted = false))
    }
}
