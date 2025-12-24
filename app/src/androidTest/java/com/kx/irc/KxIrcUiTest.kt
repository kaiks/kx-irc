package com.kx.irc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KxIrcUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetPrefs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("kx_irc_prefs", 0).edit().clear().commit()
    }

    @Test
    fun settingsFormOrderAndTlsDefaultOff() {
        ensureSettingsVisible()
        rule.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("hostField"))
        rule.onNodeWithTag("tlsSwitch").assertIsOff()
        rule.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("channelsField"))
    }

    @Test
    fun canScrollSettingsToReachChannels() {
        ensureSettingsVisible()
        rule.onNodeWithTag("settingsList")
            .performScrollToNode(hasTestTag("channelsField"))
    }

    @Test
    fun canToggleTlsSwitch() {
        ensureSettingsVisible()
        rule.onNodeWithTag("settingsList")
            .performScrollToNode(hasTestTag("tlsSwitch"))
        val tls = rule.onNodeWithTag("tlsSwitch")
        tls.performClick()
    }

    @Test
    fun canSendLocalMessageWithoutConnection() {
        ensureSettingsVisible()
        rule.onNodeWithTag("connectButton").performClick()
        rule.onNodeWithTag("contentList")
            .performScrollToNode(hasTestTag("messageField"))
        rule.onNodeWithTag("messageField").performTextInput("hello")
        rule.onNodeWithTag("sendButton").performClick()

        rule.onNodeWithTag("contentList")
            .performScrollToNode(hasTestTag("messageList"))
    }

    @Test
    fun menuOpensDrawer() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("drawer").assertIsDisplayed()
    }

    @Test
    fun drawerHasCloseButton() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("drawerClose").assertIsDisplayed()
    }

    private fun ensureSettingsVisible() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("settingsItem").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("settingsList").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
