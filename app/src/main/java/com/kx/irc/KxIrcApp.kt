@file:OptIn(ExperimentalMaterial3Api::class)

package com.kx.irc

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun KxIrcApp(viewModel: IrcViewModel = viewModel()) {
    KxIrcTheme {
        val context = LocalContext.current
        val store = remember { ConnectionStore(context) }
        val snackbarHostState = remember { SnackbarHostState() }
        var configLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            viewModel.replaceConfig(store.load())
            configLoaded = true
        }
        val feedback = viewModel.feedback
        LaunchedEffect(feedback) {
            feedback?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearFeedback(it)
            }
        }

        var showSettings by rememberSaveable { mutableStateOf(true) }
        var showJoinDialog by rememberSaveable { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val latestChatVisible by rememberUpdatedState(!showSettings)
        val latestConfigLoaded by rememberUpdatedState(configLoaded)
        LaunchedEffect(configLoaded) {
            if (configLoaded && !showSettings) {
                viewModel.reconnectOnForeground()
            }
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (latestConfigLoaded && latestChatVisible) {
                    when (event) {
                        Lifecycle.Event.ON_START -> viewModel.reconnectOnForeground()
                        Lifecycle.Event.ON_STOP -> viewModel.disconnect()
                        else -> Unit
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    viewModel = viewModel,
                    onSelect = {
                        viewModel.setTarget(it)
                        showSettings = false
                        scope.launch { drawerState.close() }
                    },
                    onClose = { scope.launch { drawerState.close() } },
                    onOpenSettings = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    },
                    onJoinChannel = {
                        showJoinDialog = true
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Header(
                        viewModel = viewModel,
                        onMenu = { scope.launch { drawerState.open() } },
                        onConnect = {
                            if (viewModel.connect()) {
                                store.save(viewModel.config)?.let(viewModel::showFeedback)
                                showSettings = false
                            }
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
                        ChatContent(
                            viewModel = viewModel,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(16.dp)
                                .imePadding()
                                .testTag("contentList")
                        )
                    }
                }
            }
        }
        if (showJoinDialog) {
            JoinChannelDialog(
                onDismiss = { showJoinDialog = false },
                onJoin = { channel ->
                    if (viewModel.joinChannel(channel)) showJoinDialog = false
                }
            )
        }
    }
}

@Composable
private fun ChatContent(viewModel: IrcViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val failedStatus = viewModel.status as? ConnectionStatus.Failed
        if (failedStatus != null) {
            Text(
                text = "Connection failed: ${failedStatus.reason}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("connectionError")
            )
        }
        MessageList(viewModel, Modifier.weight(1f))
        HorizontalDivider()
        MessageComposer(viewModel)
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
        is ConnectionStatus.Connecting, is ConnectionStatus.Failed -> "${viewModel.config.host}:${viewModel.config.port}"
        ConnectionStatus.Disconnected -> viewModel.config.host.ifBlank { "KX IRC" }
    }
    val targetLabel = viewModel.currentTarget.ifBlank { "server" }
    TopAppBar(
        title = { Text("$targetLabel — $network", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        modifier = Modifier.fillMaxWidth().testTag("settingsScroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = config.host,
            onValueChange = { viewModel.updateConfig { copy(host = it.singleLineValue()) } },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth().testTag("hostField"),
            singleLine = true
        )
        OutlinedTextField(
            value = if (config.port == 0) "" else config.port.toString(),
            onValueChange = { viewModel.updateConfig { copy(port = it.toIntOrNull() ?: 0) } },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth().testTag("portField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.serverPassword,
            onValueChange = { viewModel.updateConfig { copy(serverPassword = it.singleLineValue()) } },
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
            onValueChange = { viewModel.updateConfig { copy(nick = it.singleLineValue()) } },
            label = { Text("Nick") },
            modifier = Modifier.fillMaxWidth().testTag("nickField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.username,
            onValueChange = { viewModel.updateConfig { copy(username = it.singleLineValue()) } },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().testTag("usernameField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.realName,
            onValueChange = { viewModel.updateConfig { copy(realName = it.singleLineValue()) } },
            label = { Text("Real name") },
            modifier = Modifier.fillMaxWidth().testTag("realNameField"),
            singleLine = true
        )
        OutlinedTextField(
            value = config.channels,
            onValueChange = { viewModel.updateConfig { copy(channels = it.singleLineValue()) } },
            label = { Text("Channels (comma or space separated)") },
            modifier = Modifier.fillMaxWidth().testTag("channelsField"),
            singleLine = true
        )
    }
}

@Composable
private fun MessageList(viewModel: IrcViewModel, modifier: Modifier = Modifier) {
    val messages = viewModel.visibleMessages()
    val listState = rememberLazyListState()
    val currentTarget = viewModel.currentTarget
    val connectionGeneration = viewModel.connectionGeneration
    var shouldAutoScroll by remember(currentTarget, connectionGeneration) { mutableStateOf(true) }

    LaunchedEffect(currentTarget, connectionGeneration) {
        shouldAutoScroll = true
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && (shouldAutoScroll || isNearBottom(listState, messages.size))) {
            listState.scrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, messages.size) {
        if (messages.isNotEmpty()) shouldAutoScroll = isNearBottom(listState, messages.size)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag("messageList"),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            SelectionContainer {
                val context = LocalContext.current
                val formattedMessage = formatMessageLine(message)
                ClickableText(
                    text = formattedMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { offset ->
                        val annotation = formattedMessage.getStringAnnotations(offset, offset).firstOrNull()
                            ?: return@ClickableText
                        when (annotation.tag) {
                            LINK_ANNOTATION -> runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item)))
                            }
                            NICK_ANNOTATION -> viewModel.openPrivateChat(annotation.item)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(viewModel: IrcViewModel) {
    val currentTarget = viewModel.currentTarget
    var message by rememberSaveable(currentTarget) { mutableStateOf(viewModel.draftFor(currentTarget)) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val inputEnabled = viewModel.status is ConnectionStatus.Connected
    LaunchedEffect(currentTarget) { message = viewModel.draftFor(currentTarget) }
    val canSend = inputEnabled && message.isNotBlank()
    val sendMessage = send@{
        if (!canSend) return@send
        val targetAtSend = currentTarget
        if (!viewModel.submitComposerInput(message)) return@send
        message = ""
        viewModel.updateDraft(targetAtSend, "")
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = message,
            onValueChange = {
                message = it.singleLineValue()
                viewModel.updateDraft(currentTarget, message)
            },
            label = { Text("Message or /command") },
            trailingIcon = {
                IconButton(
                    onClick = sendMessage,
                    enabled = canSend,
                    modifier = Modifier.testTag("inlineSendButton")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { sendMessage() }),
            enabled = inputEnabled,
            modifier = Modifier.fillMaxWidth().testTag("messageField").onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == KeyEvent.KEYCODE_ENTER && native.action == KeyEvent.ACTION_UP) {
                    sendMessage()
                    true
                } else {
                    false
                }
            },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = sendMessage, enabled = canSend, modifier = Modifier.testTag("sendButton")) {
                Text("Send")
            }
            if (classifyTarget(currentTarget) == TargetKind.CHANNEL) {
                Button(
                    onClick = { viewModel.leaveCurrentChannel() },
                    enabled = inputEnabled,
                    modifier = Modifier.testTag("leaveChannelButton")
                ) {
                    Text("Leave")
                }
            }
            Button(
                onClick = {
                    val lines = viewModel.visibleMessages().takeLast(50).joinToString("\n") {
                        formatMessageLine(it).text
                    }
                    if (lines.isNotBlank()) clipboardManager.setText(AnnotatedString(lines))
                },
                modifier = Modifier.testTag("copyLastButton")
            ) {
                Text("Copy last")
            }
        }
    }
}

private fun isNearBottom(listState: LazyListState, totalItems: Int): Boolean {
    if (totalItems <= 0) return true
    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= totalItems - 2
}

@Composable
private fun DrawerContent(
    viewModel: IrcViewModel,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onJoinChannel: () -> Unit
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
            label = { Text("All messages") },
            selected = viewModel.currentTarget == "*",
            onClick = { onSelect("*") },
            modifier = Modifier.testTag("allMessagesItem")
        )
        NavigationDrawerItem(
            label = { Text("Join channel") },
            selected = false,
            onClick = onJoinChannel,
            modifier = Modifier.testTag("joinChannelItem")
        )
        viewModel.channelTargets().forEach { entry ->
            NavigationDrawerItem(
                label = { TargetLabel(entry) },
                selected = entry.name == viewModel.currentTarget,
                onClick = { onSelect(entry.name) }
            )
        }
        Text("Private", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        viewModel.privateTargets().forEach { entry ->
            NavigationDrawerItem(
                label = { TargetLabel(entry) },
                selected = entry.name == viewModel.currentTarget,
                onClick = { onSelect(entry.name) }
            )
        }
        Text("Server", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        viewModel.serverTargets().forEach { entry ->
            NavigationDrawerItem(
                label = { TargetLabel(entry) },
                selected = entry.name == viewModel.currentTarget,
                onClick = { onSelect(entry.name) }
            )
        }
        Text("Settings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        NavigationDrawerItem(
            label = { Text("Connection settings") },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.testTag("settingsItem")
        )
    }
}

@Composable
private fun TargetLabel(entry: TargetEntry) {
    val suffix = when {
        entry.mentionCount > 0 -> "  @${entry.mentionCount}"
        entry.unreadCount > 0 -> "  ${entry.unreadCount}"
        else -> ""
    }
    Text("${entry.name}$suffix", maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun JoinChannelDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var channel by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join channel") },
        text = {
            OutlinedTextField(
                value = channel,
                onValueChange = { channel = it.singleLineValue() },
                label = { Text("Channel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("joinChannelField")
            )
        },
        confirmButton = {
            TextButton(onClick = { onJoin(channel) }, enabled = channel.isNotBlank()) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

internal val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun formatMessageLine(message: IrcMessage): AnnotatedString {
    val (zncTime, cleanedBody) = extractZncTimestamp(message.body)
    val time = (zncTime ?: message.timestamp.atZone(ZoneId.systemDefault()).toLocalTime()).format(TIME_FORMATTER)
    return AnnotatedString.Builder().apply {
        append("$time (")
        val nickStart = length
        append(message.sender)
        addStringAnnotation(NICK_ANNOTATION, message.sender, nickStart, length)
        append(") ")
        if (message.isAction) append("* ")
        if (message.isNotice && message.sender != "server") append("[notice] ")
        val styledBody = buildStyledMessage(cleanedBody)
        val bodyStart = length
        append(styledBody)
        URL_PATTERN.findAll(styledBody.text).forEach { match ->
            val url = match.value.trimEnd('.', ',', '!', '?', ')', ']', '}')
            if (url.isNotEmpty()) {
                addStringAnnotation(LINK_ANNOTATION, url, bodyStart + match.range.first, bodyStart + match.range.first + url.length)
            }
        }
    }.toAnnotatedString()
}

internal fun extractZncTimestamp(body: String): Pair<LocalTime?, String> {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("[")) return Pair(null, body)
    val end = trimmed.indexOf(']')
    if (end <= 1) return Pair(null, body)
    val time = runCatching { LocalTime.parse(trimmed.substring(1, end), TIME_FORMATTER) }.getOrNull()
    return if (time != null) Pair(time, trimmed.substring(end + 1).trimStart()) else Pair(null, body)
}

private fun String.singleLineValue(): String = replace("\r", "").replace("\n", "")

internal const val LINK_ANNOTATION = "link"
internal const val NICK_ANNOTATION = "nick"
private val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
