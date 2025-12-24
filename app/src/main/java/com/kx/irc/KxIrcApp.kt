package com.kx.irc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

@Composable
fun KxIrcApp(viewModel: IrcViewModel = viewModel()) {
    KxIrcTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).imePadding()) {
                Header(viewModel)
                Spacer(modifier = Modifier.height(12.dp))
                ConnectionForm(viewModel)
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                TargetRow(viewModel)
                Spacer(modifier = Modifier.height(8.dp))
                MessageList(viewModel)
                Spacer(modifier = Modifier.height(12.dp))
                MessageComposer(viewModel)
            }
        }
    }
}

@Composable
private fun Header(viewModel: IrcViewModel) {
    val status = viewModel.status
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("KX IRC", style = MaterialTheme.typography.titleLarge)
            Text(
                text = when (status) {
                    is ConnectionStatus.Connected -> "Connected to ${status.server}"
                    is ConnectionStatus.Connecting -> "Connecting..."
                    is ConnectionStatus.Failed -> "Failed: ${status.reason}"
                    ConnectionStatus.Disconnected -> "Disconnected"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val isConnected = status is ConnectionStatus.Connected || status is ConnectionStatus.Connecting
        Button(
            onClick = { if (isConnected) viewModel.disconnect() else viewModel.connect() },
            modifier = Modifier.testTag("connectButton")
        ) {
            Text(if (isConnected) "Disconnect" else "Connect")
        }
    }
}

@Composable
private fun ConnectionForm(viewModel: IrcViewModel) {
    val config = viewModel.config
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
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
private fun TargetRow(viewModel: IrcViewModel) {
    val targets = viewModel.targets
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("targetsRow")) {
        items(targets) { target ->
            val label = if (target == "*") "All" else target
            Button(onClick = { viewModel.setTarget(target) }) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MessageList(viewModel: IrcViewModel) {
    val messages = viewModel.visibleMessages()
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).testTag("messageList")) {
        items(messages, key = { it.id }) { message ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = "${message.sender} -> ${message.target}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildStyledMessage(message.body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(viewModel: IrcViewModel) {
    var message by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = viewModel.currentTarget,
            onValueChange = { viewModel.setTarget(it) },
            label = { Text("Target") },
            modifier = Modifier.fillMaxWidth().testTag("targetField"),
            singleLine = true
        )
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth().testTag("messageField").onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == KeyEvent.KEYCODE_ENTER && native.action == KeyEvent.ACTION_UP) {
                    viewModel.sendMessage(message)
                    message = ""
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
            },
            modifier = Modifier.testTag("sendButton")
        ) {
            Text("Send")
        }
    }
}
