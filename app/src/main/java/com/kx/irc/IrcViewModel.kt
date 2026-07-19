package com.kx.irc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
    private var ownNick by mutableStateOf("")

    var connectionGeneration by mutableStateOf(0)
        private set
    val messages = mutableStateListOf<IrcMessage>()
    private val targetMeta = mutableStateListOf<TargetEntry>()
    private val drafts = mutableStateMapOf<String, String>()
    private val channelRosters = mutableStateMapOf<String, List<ChannelMember>>()

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
            client.currentNick.collectLatest { ownNick = it }
        }
        viewModelScope.launch {
            client.channelRosters.collectLatest { update ->
                channelRosters[draftKey(update.channel)] = update.members
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
        return startConnection(clearHistory = true, showValidationError = true)
    }

    /** Reconnect only after the activity returns to a visible chat screen. */
    fun reconnectOnForeground(): Boolean {
        if (!shouldReconnectOnForeground(status, config)) return false
        return startConnection(clearHistory = false, showValidationError = false)
    }

    private fun startConnection(clearHistory: Boolean, showValidationError: Boolean): Boolean {
        val error = config.validate()
        if (error != null) {
            status = ConnectionStatus.Failed(error)
            if (showValidationError) feedback = error
            return false
        }
        if (!client.connect(config)) return false

        // Update immediately so a lifecycle callback cannot schedule a second connection.
        status = ConnectionStatus.Connecting
        val previousTarget = currentTarget
        if (clearHistory) messages.clear()
        if (clearHistory) channelRosters.clear()
        syncTargetsFromConfig()
        currentTarget = pickTargetAfterConnect(
            previousTarget = previousTarget,
            configuredChannels = config.channelList(),
            knownChannelTargets = channelTargets().map { it.name },
            knownPrivateTargets = privateTargets().map { it.name }
        )
        ensureTarget(currentTarget)
        connectionGeneration += 1
        return true
    }

    fun disconnect() {
        client.disconnect()
    }

    fun setTarget(target: String) {
        if (target == "*") {
            currentTarget = target
            clearAllUnread()
            return
        }
        currentTarget = targetMeta.firstOrNull { ircEquals(it.name, target, caseMapping) }?.name
            ?: target.ifBlank { "server" }
        clearUnread(currentTarget)
    }

    fun sendMessage(message: String) {
        client.sendMessage(currentTarget, message)
    }

    fun submitComposerInput(input: String): Boolean {
        return when (val action = parseComposerAction(input, currentTarget)) {
            is ComposerAction.Say -> {
                client.sendMessage(action.target, action.message)
                true
            }
            is ComposerAction.Action -> {
                client.sendAction(action.target, action.message)
                true
            }
            is ComposerAction.Join -> joinChannel(action.channel)
            is ComposerAction.Part -> partChannel(action.channel, action.reason)
            is ComposerAction.Message -> {
                openPrivateChat(action.target)
                client.sendMessage(action.target, action.message)
                true
            }
            is ComposerAction.Nick -> {
                client.changeNick(action.nick)
                true
            }
            is ComposerAction.Error -> {
                feedback = action.reason
                false
            }
        }
    }

    fun joinChannel(channel: String): Boolean {
        if (status !is ConnectionStatus.Connected) {
            feedback = "Connect before joining a channel"
            return false
        }
        if (classifyTarget(channel) != TargetKind.CHANNEL || !isValidIrcTarget(channel)) {
            feedback = "Enter a valid channel name"
            return false
        }
        ensureTarget(channel)
        setTarget(channel)
        client.joinChannel(channel)
        return true
    }

    fun leaveCurrentChannel(): Boolean = partChannel(currentTarget, null)

    fun openPrivateChat(nick: String) {
        if (!isValidIrcTarget(nick) || ircEquals(nick, ownNick, caseMapping)) return
        ensureTarget(nick)
        setTarget(nick)
    }

    fun draftFor(target: String): String = drafts[draftKey(target)].orEmpty()

    fun updateDraft(target: String, value: String) {
        val key = draftKey(target)
        if (value.isBlank()) drafts.remove(key) else drafts[key] = value
    }

    fun mentionSuggestions(target: String, draft: String): List<String> {
        if (classifyTarget(target) != TargetKind.CHANNEL) return emptyList()
        val nicknames = channelRosters[draftKey(target)].orEmpty().map(ChannelMember::nick)
        return findMentionSuggestions(draft, nicknames, ownNick, caseMapping)
    }

    fun insertMention(draft: String, nick: String): String = insertMentionSuggestion(draft, nick)

    fun channelMembers(channel: String = currentTarget): List<ChannelMember> =
        if (classifyTarget(channel) == TargetKind.CHANNEL) channelRosters[draftKey(channel)].orEmpty() else emptyList()

    fun isOwnNick(nick: String): Boolean = ownNick.isNotBlank() && ircEquals(nick, ownNick, caseMapping)

    fun canModerateChannel(channel: String = currentTarget): Boolean =
        channelMembers(channel).any { isOwnNick(it.nick) && it.isOperator }

    fun kickMember(channel: String, nick: String): Boolean {
        if (!canModerate(channel, nick)) return false
        client.kick(channel, nick)
        return true
    }

    fun voiceMember(channel: String, nick: String): Boolean {
        if (!canModerate(channel, nick)) return false
        client.setChannelMemberMode(channel, nick, 'v')
        return true
    }

    fun opMember(channel: String, nick: String): Boolean {
        if (!canModerate(channel, nick)) return false
        client.setChannelMemberMode(channel, nick, 'o')
        return true
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
        val wasVisible = currentTarget == "*" || ircEquals(currentTarget, derived, caseMapping)
        ensureTarget(derived)
        if (!wasVisible && classifyTarget(derived) != TargetKind.SERVER && !isOwnMessage(message)) {
            markUnread(derived, message)
        }
        if (ircEquals(currentTarget, "server", caseMapping) && classifyTarget(derived) == TargetKind.CHANNEL) {
            currentTarget = derived
            clearUnread(derived)
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

    private fun partChannel(channel: String, reason: String?): Boolean {
        if (status !is ConnectionStatus.Connected) {
            feedback = "Connect before leaving a channel"
            return false
        }
        if (classifyTarget(channel) != TargetKind.CHANNEL || !isValidIrcTarget(channel)) {
            feedback = "Select a channel to leave"
            return false
        }
        client.partChannel(channel, reason)
        removeTarget(channel)
        return true
    }

    private fun removeTarget(name: String) {
        val index = targetMeta.indexOfFirst { ircEquals(it.name, name, caseMapping) }
        if (index != -1) targetMeta.removeAt(index)
        if (ircEquals(currentTarget, name, caseMapping)) currentTarget = "server"
    }

    private fun markUnread(target: String, message: IrcMessage) {
        val index = targetMeta.indexOfFirst { ircEquals(it.name, target, caseMapping) }
        if (index == -1) return
        val isMention = classifyTarget(target) == TargetKind.PRIVATE || isIrcMention(message.body, ownNick, caseMapping)
        targetMeta[index] = targetMeta[index].copy(
            unreadCount = targetMeta[index].unreadCount + 1,
            mentionCount = targetMeta[index].mentionCount + if (isMention) 1 else 0
        )
    }

    private fun clearUnread(target: String) {
        val index = targetMeta.indexOfFirst { ircEquals(it.name, target, caseMapping) }
        if (index != -1 && (targetMeta[index].unreadCount != 0 || targetMeta[index].mentionCount != 0)) {
            targetMeta[index] = targetMeta[index].copy(unreadCount = 0, mentionCount = 0)
        }
    }

    private fun clearAllUnread() {
        targetMeta.indices.forEach { index ->
            if (targetMeta[index].unreadCount != 0 || targetMeta[index].mentionCount != 0) {
                targetMeta[index] = targetMeta[index].copy(unreadCount = 0, mentionCount = 0)
            }
        }
    }

    private fun isOwnMessage(message: IrcMessage): Boolean =
        ownNick.isNotBlank() && ircEquals(message.sender, ownNick, caseMapping)

    private fun canModerate(channel: String, nick: String): Boolean {
        if (status !is ConnectionStatus.Connected || classifyTarget(channel) != TargetKind.CHANNEL) {
            feedback = "Connect to a channel before moderating members"
            return false
        }
        if (isOwnNick(nick) || channelMembers(channel).none { ircEquals(it.nick, nick, caseMapping) }) return false
        if (!canModerateChannel(channel)) {
            feedback = "Channel operator status is required"
            return false
        }
        return true
    }

    private fun draftKey(target: String): String = ircCaseFold(target, caseMapping)

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
    val lastActivity: Long,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0
)

internal fun pickTargetAfterConnect(
    previousTarget: String,
    configuredChannels: List<String>,
    knownChannelTargets: List<String>,
    knownPrivateTargets: List<String>
): String {
    val normalizedPrevious = previousTarget.ifBlank { "server" }
    if (!normalizedPrevious.equals("server", ignoreCase = true)) return normalizedPrevious
    configuredChannels.firstOrNull()?.let { return it }
    knownChannelTargets.firstOrNull()?.let { return it }
    knownPrivateTargets.firstOrNull()?.let { return it }
    return "server"
}

internal fun shouldReconnectOnForeground(
    status: ConnectionStatus,
    config: IrcConfig
): Boolean {
    val reconnectable = status is ConnectionStatus.Disconnected || status is ConnectionStatus.Failed
    if (!reconnectable) return false
    return config.validate() == null
}
