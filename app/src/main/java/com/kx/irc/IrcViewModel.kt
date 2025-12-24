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

    var currentTarget by mutableStateOf("server")
        private set

    val messages = mutableStateListOf<IrcMessage>()
    private val targetMeta = mutableStateListOf<TargetEntry>()

    init {
        ensureTarget("server")
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

    fun replaceConfig(newConfig: IrcConfig) {
        config = newConfig
    }

    fun connect() {
        val error = config.validate()
        if (error != null) {
            status = ConnectionStatus.Failed(error)
            return
        }
        syncTargetsFromConfig()
        client.connect(config)
        currentTarget = preferredTargetAfterConnect()
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

    fun channelTargets(): List<TargetEntry> =
        targetMeta.filter { it.kind == TargetKind.CHANNEL }.sortedByDescending { it.lastActivity }

    fun privateTargets(): List<TargetEntry> =
        targetMeta.filter { it.kind == TargetKind.PRIVATE }.sortedByDescending { it.lastActivity }

    fun serverTargets(): List<TargetEntry> =
        targetMeta.filter { it.kind == TargetKind.SERVER }.sortedByDescending { it.lastActivity }

    fun preferredTargetAfterConnect(): String {
        val channel = channelTargets().firstOrNull()?.name ?: config.channelList().firstOrNull()
        if (!channel.isNullOrBlank()) return channel
        val privateTarget = privateTargets().firstOrNull()?.name
        if (!privateTarget.isNullOrBlank()) return privateTarget
        return "server"
    }

    override fun onCleared() {
        client.shutdown()
    }

    private fun ensureTargetForMessage(message: IrcMessage) {
        val derived = message.target.ifBlank { "server" }
        ensureTarget(derived)
    }

    private fun syncTargetsFromConfig() {
        val configured = config.channelList()
        if (configured.isEmpty()) return
        configured.forEach { ensureTarget(it) }
    }

    private fun ensureTarget(name: String) {
        val key = name.ifBlank { "server" }
        val kind = classifyTarget(key)
        val now = System.currentTimeMillis()
        val index = targetMeta.indexOfFirst { it.name == key }
        if (index == -1) {
            targetMeta.add(TargetEntry(key, kind, now))
        } else {
            targetMeta[index] = targetMeta[index].copy(lastActivity = now)
        }
    }
}

data class TargetEntry(
    val name: String,
    val kind: TargetKind,
    val lastActivity: Long
)
