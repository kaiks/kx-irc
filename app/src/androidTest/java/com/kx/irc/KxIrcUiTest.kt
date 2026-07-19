package com.kx.irc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KxIrcUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var silentServer: ServerSocket

    @Before
    fun resetPrefs() {
        silentServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("kx_irc_prefs", 0).edit().clear().commit()
        rule.activityRule.scenario.recreate()
    }

    @After
    fun closeSilentServer() {
        silentServer.close()
    }

    @Test
    fun settingsFormOrderAndTlsDefaultOn() {
        ensureSettingsVisible()
        rule.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("hostField"))
        rule.onNodeWithTag("tlsSwitch").assertIsOn()
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
        tls.assertIsOff()
    }

    @Test
    fun messageInputIsDisabledUntilConnected() {
        ensureSettingsVisible()
        enterSilentServer()
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("connectButton").performClick()

        rule.onNodeWithTag("contentList").assertIsDisplayed()
        rule.onNodeWithTag("messageField").assertIsNotEnabled()
        rule.onNodeWithTag("sendButton").assertIsNotEnabled()
        rule.onNodeWithTag("inlineSendButton").assertIsNotEnabled()
    }

    @Test
    fun inlineSendButtonPresentInComposer() {
        ensureSettingsVisible()
        enterSilentServer()
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("connectButton").performClick()
        rule.onNodeWithTag("inlineSendButton").assertIsDisplayed()
    }

    @Test
    fun channelChatOffersMemberSheet() {
        ensureSettingsVisible()
        enterSilentServer()
        rule.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("channelsField"))
        rule.onNodeWithTag("channelsField").performTextInput("#general")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("connectButton").performClick()

        rule.onNodeWithTag("membersButton").assertIsDisplayed().performClick()
        rule.onNodeWithTag("membersSheet").assertIsDisplayed()
        rule.onNodeWithTag("membersEmpty").assertIsDisplayed()
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

    @Test
    fun drawerOffersAllMessagesView() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("allMessagesItem").assertIsDisplayed()
    }

    @Test
    fun drawerOffersJoinChannelAction() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("joinChannelItem").performClick()
        rule.onNodeWithTag("joinChannelField").assertIsDisplayed()
    }

    private fun ensureSettingsVisible() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("settingsItem").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("settingsList").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun enterSilentServer() {
        rule.onNodeWithTag("hostField").performTextInput("127.0.0.1")
        rule.onNodeWithTag("portField").performTextClearance()
        rule.onNodeWithTag("portField").performTextInput(silentServer.localPort.toString())
    }
}
