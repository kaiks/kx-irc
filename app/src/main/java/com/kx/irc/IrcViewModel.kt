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

    var feedback by mutableStateOf<String?>(null)
        private set

    private var caseMapping by mutableStateOf(IrcCaseMapping.RFC1459)

    val messages = mutableStateListOf<IrcMessage>()
    private val targetMeta = mutableStateListOf<TargetEntry>()

    init {
        ensureTarget("server")
        viewModelScope.launch {
            client.status.collectLatest { status = it }
        }
        viewModelScope.launch {
            client.events.collect { feedback = it }
        }
        viewModelScope.launch {
            client.caseMapping.collectLatest {
                caseMapping = it
                mergeEquivalentTargets()
            }
        }
        viewModelScope.launch {
            client.messages.collect { message ->
                appendMessage(message)
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

    fun connect(): Boolean {
        val error = config.validate()
        if (error != null) {
            status = ConnectionStatus.Failed(error)
            feedback = error
            return false
        }
        messages.clear()
        syncTargetsFromConfig()
        client.connect(config)
        currentTarget = preferredTargetAfterConnect()
        return true
    }

    fun disconnect() {
        client.disconnect()
    }

    fun setTarget(target: String) {
        if (target == "*") {
            currentTarget = target
            return
        }
        currentTarget = targetMeta.firstOrNull { ircEquals(it.name, target, caseMapping) }?.name
            ?: target.ifBlank { "server" }
    }

    fun sendMessage(message: String) {
        client.sendMessage(currentTarget, message)
    }

    fun visibleMessages(): List<IrcMessage> =
        filterMessagesByTarget(messages, currentTarget, caseMapping)
            .sortedWith(compareBy<IrcMessage> { it.timestamp }.thenBy { it.id })

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

    fun showFeedback(message: String) {
        feedback = message
    }

    fun clearFeedback(message: String) {
        if (feedback == message) feedback = null
    }

    override fun onCleared() {
        client.shutdown()
    }

    private fun ensureTargetForMessage(message: IrcMessage) {
        val derived = message.target.ifBlank { "server" }
        ensureTarget(derived)
        if (ircEquals(currentTarget, "server", caseMapping) && classifyTarget(derived) == TargetKind.CHANNEL) {
            currentTarget = derived
        }
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
        val index = targetMeta.indexOfFirst { ircEquals(it.name, key, caseMapping) }
        if (index == -1) {
            targetMeta.add(TargetEntry(key, kind, now))
        } else {
            targetMeta[index] = targetMeta[index].copy(lastActivity = now)
        }
    }

    private fun appendMessage(message: IrcMessage) {
        messages.add(message)
        val target = message.target.ifBlank { "server" }
        while (messages.count { ircEquals(it.target, target, caseMapping) } > MAX_MESSAGES_PER_TARGET) {
            val index = messages.indexOfFirst { ircEquals(it.target, target, caseMapping) }
            if (index == -1) break
            messages.removeAt(index)
        }
        while (messages.size > MAX_MESSAGES_TOTAL) {
            messages.removeAt(0)
        }
    }

    private fun mergeEquivalentTargets() {
        val merged = mutableListOf<TargetEntry>()
        targetMeta.forEach { entry ->
            val existingIndex = merged.indexOfFirst { ircEquals(it.name, entry.name, caseMapping) }
            if (existingIndex == -1) {
                merged += entry
            } else if (entry.lastActivity > merged[existingIndex].lastActivity) {
                merged[existingIndex] = entry
            }
        }
        if (merged != targetMeta) {
            targetMeta.clear()
            targetMeta.addAll(merged)
        }
    }

    private companion object {
        const val MAX_MESSAGES_PER_TARGET = 500
        const val MAX_MESSAGES_TOTAL = 2_000
    }
}

data class TargetEntry(
    val name: String,
    val kind: TargetKind,
    val lastActivity: Long
)
