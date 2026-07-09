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
}
