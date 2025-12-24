@file:OptIn(ExperimentalMaterial3Api::class)

package com.kx.irc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.AnnotatedString
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.rememberCoroutineScope
import java.time.LocalTime

@Composable
fun KxIrcApp(viewModel: IrcViewModel = viewModel()) {
    KxIrcTheme {
        val context = LocalContext.current
        val store = remember { ConnectionStore(context) }
        LaunchedEffect(Unit) {
            viewModel.replaceConfig(store.load())
        }
        var showSettings by remember { mutableStateOf(true) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    viewModel = viewModel,
                    onSelect = {
                        viewModel.setTarget(it)
                        scope.launch { drawerState.close() }
                    },
                    onClose = { scope.launch { drawerState.close() } },
                    onOpenSettings = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    Header(
                        viewModel = viewModel,
                        onMenu = { scope.launch { drawerState.open() } },
                        onConnect = {
                            store.save(viewModel.config)
                            viewModel.connect()
                            showSettings = false
                        },
                        onDisconnect = {
                            viewModel.disconnect()
                            showSettings = true
                        }
                    )
                }
            ) { padding ->
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showSettings) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(16.dp)
                                .imePadding()
                                .testTag("settingsList"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item { ConnectionForm(viewModel) }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(16.dp)
                                .imePadding()
                                .testTag("contentList"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item { MessageList(viewModel) }
                            item { MessageComposer(viewModel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    viewModel: IrcViewModel,
    onMenu: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status = viewModel.status
    val network = when (status) {
        is ConnectionStatus.Connected -> status.server
        is ConnectionStatus.Connecting -> "${viewModel.config.host}:${viewModel.config.port}"
        is ConnectionStatus.Failed -> "${viewModel.config.host}:${viewModel.config.port}"
        ConnectionStatus.Disconnected -> viewModel.config.host.ifBlank { "KX IRC" }
    }
    TopAppBar(
        title = { Text(network, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onMenu, modifier = Modifier.testTag("menuButton")) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
        },
        actions = {
            val isConnected = status is ConnectionStatus.Connected || status is ConnectionStatus.Connecting
            Button(
                onClick = { if (isConnected) onDisconnect() else onConnect() },
                modifier = Modifier.testTag("connectButton").widthIn(min = 120.dp)
            ) {
                Text(if (isConnected) "Disconnect" else "Connect")
            }
        }
    )
}

@Composable
private fun ConnectionForm(viewModel: IrcViewModel) {
    val config = viewModel.config
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settingsScroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = config.host,
            onValueChange = { viewModel.updateConfig { copy(host = it) } },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth().testTag("hostField"),
            singleLine = true
        )
        OutlinedTextField(
            value = if (config.port == 0) "" else config.port.toString(),
            onValueChange = {
                viewModel.updateConfig { copy(port = it.toIntOrNull() ?: 0) }
            },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth().testTag("portField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.serverPassword,
            onValueChange = { viewModel.updateConfig { copy(serverPassword = it) } },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth().testTag("passwordField"),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use TLS")
            Switch(
                checked = config.useTls,
                onCheckedChange = { viewModel.updateConfig { copy(useTls = it) } },
                modifier = Modifier.testTag("tlsSwitch")
            )
        }
        OutlinedTextField(
            value = config.nick,
            onValueChange = { viewModel.updateConfig { copy(nick = it) } },
            label = { Text("Nick") },
            modifier = Modifier.fillMaxWidth().testTag("nickField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.username,
            onValueChange = { viewModel.updateConfig { copy(username = it) } },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().testTag("usernameField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.realName,
            onValueChange = { viewModel.updateConfig { copy(realName = it) } },
            label = { Text("Real name") },
            modifier = Modifier.fillMaxWidth().testTag("realNameField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.channels,
            onValueChange = { viewModel.updateConfig { copy(channels = it) } },
            label = { Text("Channels (comma or space separated)") },
            modifier = Modifier.fillMaxWidth().testTag("channelsField"),
            singleLine = true
        )
    }
}

@Composable
private fun MessageList(viewModel: IrcViewModel) {
    val messages = viewModel.visibleMessages()
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 1
        }
    }
    val currentTarget = viewModel.currentTarget

    LaunchedEffect(currentTarget) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && atBottom) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 420.dp)
            .testTag("messageList"),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            SelectionContainer {
                Text(
                    text = formatMessageLine(message),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(viewModel: IrcViewModel) {
    var message by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth().testTag("messageField").onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == KeyEvent.KEYCODE_ENTER && native.action == KeyEvent.ACTION_UP) {
                    viewModel.sendMessage(message)
                    message = ""
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    true
                } else {
                    false
                }
            },
            singleLine = true
        )
        Button(
            onClick = {
                viewModel.sendMessage(message)
                message = ""
                focusManager.clearFocus()
                keyboardController?.hide()
            },
            modifier = Modifier.testTag("sendButton")
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun DrawerContent(
    viewModel: IrcViewModel,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.testTag("drawer")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Channels", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onClose, modifier = Modifier.testTag("drawerClose")) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
        NavigationDrawerItem(
            label = { Text("Connection settings") },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.testTag("settingsItem")
        )
        viewModel.channelTargets().forEach { entry ->
            NavigationDrawerItem(
                label = { Text(entry.name) },
                selected = entry.name == viewModel.currentTarget,
                onClick = { onSelect(entry.name) }
            )
        }
        Text("Private", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        viewModel.privateTargets().forEach { entry ->
            NavigationDrawerItem(
                label = { Text(entry.name) },
                selected = entry.name == viewModel.currentTarget,
                onClick = { onSelect(entry.name) }
            )
        }
        Text("Server", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        viewModel.serverTargets().forEach { entry ->
            NavigationDrawerItem(
                label = { Text(entry.name) },
                selected = entry.name == viewModel.currentTarget,
                onClick = { onSelect(entry.name) }
            )
        }
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatMessageLine(message: IrcMessage): AnnotatedString {
    val (zncTime, cleanedBody) = extractZncTimestamp(message.body)
    val time = (zncTime ?: message.timestamp.atZone(ZoneId.systemDefault()).toLocalTime())
        .format(TIME_FORMATTER)
    val prefix = "$time (${message.sender}) "
    val body = buildStyledMessage(cleanedBody).text
    return AnnotatedString(prefix + body)
}

private fun extractZncTimestamp(body: String): Pair<LocalTime?, String> {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("[")) return Pair(null, body)
    val end = trimmed.indexOf(']')
    if (end <= 1) return Pair(null, body)
    val timeCandidate = trimmed.substring(1, end)
    val time = runCatching { LocalTime.parse(timeCandidate, TIME_FORMATTER) }.getOrNull()
    return if (time != null) {
        val remainder = trimmed.substring(end + 1).trimStart()
        Pair(time, remainder)
    } else {
        Pair(null, body)
    }
}
