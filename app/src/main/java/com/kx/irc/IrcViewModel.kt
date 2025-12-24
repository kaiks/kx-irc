package com.kx.irc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IrcViewModel : ViewModel() {
    private val client = IrcClient()

    var config by mutableStateOf(IrcConfig())
        private set

    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected)
        private set

    var currentTarget by mutableStateOf("")
        private set

    val messages = mutableStateListOf<IrcMessage>()
    val targets = mutableStateListOf<String>()

    init {
        targets.add("*")
        viewModelScope.launch {
            client.status.collectLatest { status = it }
        }
        viewModelScope.launch {
            client.messages.collectLatest { message ->
                messages.add(message)
                ensureTargetForMessage(message)
            }
        }
    }

    fun updateConfig(update: IrcConfig.() -> IrcConfig) {
        config = config.update()
    }

    fun connect() {
        val error = config.validate()
        if (error != null) {
            status = ConnectionStatus.Failed(error)
            return
        }
        syncTargetsFromConfig()
        client.connect(config)
        if (currentTarget.isBlank()) {
            currentTarget = config.channelList().firstOrNull() ?: "*"
        }
    }

    fun disconnect() {
        client.disconnect()
    }

    fun setTarget(target: String) {
        currentTarget = target
    }

    fun sendMessage(message: String) {
        client.sendMessage(currentTarget, message)
    }

    fun visibleMessages(): List<IrcMessage> =
        filterMessagesByTarget(messages, currentTarget)

    override fun onCleared() {
        client.shutdown()
    }

    private fun ensureTargetForMessage(message: IrcMessage) {
        val derived = if (message.target.startsWith("#")) message.target else message.sender
        if (derived.isNotBlank() && !targets.contains(derived)) {
            targets.add(derived)
        }
    }

    private fun syncTargetsFromConfig() {
        val configured = config.channelList()
        if (configured.isEmpty()) return
        configured.forEach {
            if (!targets.contains(it)) targets.add(it)
        }
    }
}
