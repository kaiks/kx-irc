package com.kx.irc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import org.junit.Rule
import org.junit.Test

class KxIrcUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsFormOrderAndTlsDefaultOff() {
        rule.onNodeWithTag("hostField").assertIsDisplayed()
        rule.onNodeWithTag("portField").assertIsDisplayed()
        rule.onNodeWithTag("passwordField").assertIsDisplayed()
        rule.onNodeWithTag("tlsSwitch").assertIsOff()
        rule.onNodeWithTag("nickField").assertIsDisplayed()
        rule.onNodeWithTag("usernameField").assertIsDisplayed()
        rule.onNodeWithTag("realNameField").assertIsDisplayed()
        rule.onNodeWithTag("channelsField").assertIsDisplayed()
    }

    @Test
    fun canScrollSettingsToReachChannels() {
        rule.onNodeWithTag("contentList")
            .performScrollToNode(hasTestTag("channelsField"))
        rule.onNodeWithTag("channelsField").assertIsDisplayed()
    }

    @Test
    fun canToggleTlsSwitch() {
        val tls = rule.onNodeWithTag("tlsSwitch")
        tls.assertIsOff()
        tls.performClick()
        tls.assertIsOn()
    }

    @Test
    fun canSendLocalMessageWithoutConnection() {
        rule.onNodeWithTag("targetField").performTextInput("#one")
        rule.onNodeWithTag("messageField").performTextInput("hello")
        rule.onNodeWithTag("sendButton").performClick()

        rule.onNodeWithTag("messageList").assertIsDisplayed()
    }
}
