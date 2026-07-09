package com.kx.irc

import java.time.Instant

private const val IRC_MAX_LINE_BYTES = 512

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data class Connected(val server: String) : ConnectionStatus()
    data class Failed(val reason: String) : ConnectionStatus()
}

data class IrcConfig(
    val host: String = "",
    val port: Int = 6697,
    val useTls: Boolean = true,
    val nick: String = "",
    val username: String = "",
    val realName: String = "",
    val channels: String = "",
    val serverPassword: String = ""
)

data class IrcMessage(
    val id: Long,
    val timestamp: Instant,
    val sender: String,
    val target: String,
    val body: String,
    val isNotice: Boolean = false,
    val isAction: Boolean = false
)

data class ChannelRosterUpdate(
    val channel: String,
    val nicknames: List<String>
)

enum class TargetKind { SERVER, CHANNEL, PRIVATE }

fun IrcConfig.toAuthPassword(): String {
    return serverPassword
}

fun IrcConfig.channelList(): List<String> =
    channels.split(',', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { ircCaseFold(it) }

fun IrcConfig.validate(): String? {
    if (host.isBlank()) return "Host is required"
    if (host.any(::isIrcLineBreakOrControl) || host.any(Char::isWhitespace)) {
        return "Host cannot contain whitespace or control characters"
    }
    if (port !in 1..65535) return "Port must be between 1 and 65535"
    configTokenError("Nick", nick)?.let { return it }
    configTokenError("Username", username)?.let { return it }
    if (realName.any(::isIrcLineBreakOrControl)) return "Real name cannot contain line breaks"
    if (serverPassword.any(::isIrcLineBreakOrControl)) return "Password cannot contain line breaks"
    channelList().firstOrNull { !isValidIrcTarget(it) }?.let {
        return "Invalid channel: $it"
    }
    return null
}

fun filterMessagesByTarget(
    messages: List<IrcMessage>,
    target: String,
    caseMapping: IrcCaseMapping = IrcCaseMapping.RFC1459
): List<IrcMessage> {
    if (target.isBlank() || target == "*") return messages
    return messages.filter { ircEquals(it.target, target, caseMapping) }
}

fun classifyTarget(name: String): TargetKind {
    if (name.equals("server", ignoreCase = true)) return TargetKind.SERVER
    if (name.startsWith("#") || name.startsWith("&") || name.startsWith("+") || name.startsWith("!")) {
        return TargetKind.CHANNEL
    }
    return TargetKind.PRIVATE
}

enum class IrcCaseMapping {
    ASCII,
    RFC1459,
    STRICT_RFC1459
}

fun ircCaseFold(value: String, caseMapping: IrcCaseMapping = IrcCaseMapping.RFC1459): String =
    buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    in 'A'..'Z' -> character.lowercaseChar()
                    '[' -> if (caseMapping == IrcCaseMapping.ASCII) '[' else '{'
                    ']' -> if (caseMapping == IrcCaseMapping.ASCII) ']' else '}'
                    '\\' -> if (caseMapping == IrcCaseMapping.ASCII) '\\' else '|'
                    '^' -> if (caseMapping == IrcCaseMapping.RFC1459) '~' else '^'
                    else -> character
                }
            )
        }
    }

fun ircEquals(left: String, right: String, caseMapping: IrcCaseMapping = IrcCaseMapping.RFC1459): Boolean =
    ircCaseFold(left, caseMapping) == ircCaseFold(right, caseMapping)

internal fun splitPrivmsgCommands(target: String, message: String): List<String> =
    splitPrivmsgBodies(target, message).map { "PRIVMSG $target :$it" }

internal fun splitPrivmsgBodies(target: String, message: String, reservedBytes: Int = 0): List<String> {
    require(isValidIrcTarget(target)) { "Invalid IRC target" }
    require(message.isNotBlank()) { "Message is required" }
    require(message.none(::isIrcLineBreakOrControl)) { "Messages cannot contain line breaks or control characters" }

    val prefix = "PRIVMSG $target :"
    val availableBytes = IRC_MAX_LINE_BYTES - "\r\n".toByteArray(Charsets.UTF_8).size -
        prefix.toByteArray(Charsets.UTF_8).size - reservedBytes
    require(availableBytes > 0) { "IRC target is too long" }

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < message.length) {
        var end = start
        var byteCount = 0
        var lastWhitespaceEnd = -1
        while (end < message.length) {
            val codePoint = Character.codePointAt(message, end)
            val characterCount = Character.charCount(codePoint)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (byteCount + bytes > availableBytes) break
            byteCount += bytes
            end += characterCount
            if (codePoint.toChar().isWhitespace()) lastWhitespaceEnd = end
        }
        require(end > start) { "A character in the message is too large to send" }
        val splitAt = if (end < message.length && lastWhitespaceEnd > start) lastWhitespaceEnd else end
        chunks += message.substring(start, splitAt)
        start = splitAt
    }
    return chunks
}

internal fun isValidIrcTarget(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() || isIrcLineBreakOrControl(it) || it == ':' || it == ',' }

internal fun isSafeIrcLine(line: String): Boolean =
    line.none { isIrcLineBreakOrControl(it) && it != CTCP_DELIMITER } &&
        "${line}\r\n".toByteArray(Charsets.UTF_8).size <= IRC_MAX_LINE_BYTES

internal sealed interface ComposerAction {
    data class Say(val target: String, val message: String) : ComposerAction
    data class Action(val target: String, val message: String) : ComposerAction
    data class Join(val channel: String) : ComposerAction
    data class Part(val channel: String, val reason: String?) : ComposerAction
    data class Message(val target: String, val message: String) : ComposerAction
    data class Nick(val nick: String) : ComposerAction
    data class Error(val reason: String) : ComposerAction
}

internal fun parseComposerAction(input: String, currentTarget: String): ComposerAction {
    if (input.isBlank()) return ComposerAction.Error("Message is required")
    if (!input.startsWith('/')) {
        return if (isChatTarget(currentTarget)) ComposerAction.Say(currentTarget, input)
        else ComposerAction.Error("Select a channel or private conversation first")
    }

    val commandLine = input.drop(1).trimStart()
    val command = commandLine.substringBefore(' ').lowercase()
    val arguments = commandLine.substringAfter(' ', "").trim()
    return when (command) {
        "join", "j" -> {
            if (classifyTarget(arguments) == TargetKind.CHANNEL && isValidIrcTarget(arguments)) {
                ComposerAction.Join(arguments)
            } else {
                ComposerAction.Error("Usage: /join #channel")
            }
        }
        "part", "leave" -> {
            val first = arguments.substringBefore(' ')
            val channel = if (classifyTarget(first) == TargetKind.CHANNEL) first else currentTarget
            val reason = if (channel == first) arguments.substringAfter(' ', "").ifBlank { null } else arguments.ifBlank { null }
            if (classifyTarget(channel) == TargetKind.CHANNEL && isValidIrcTarget(channel)) {
                ComposerAction.Part(channel, reason)
            } else {
                ComposerAction.Error("Usage: /part [#channel] [reason]")
            }
        }
        "me" -> {
            if (arguments.isNotBlank() && isChatTarget(currentTarget)) ComposerAction.Action(currentTarget, arguments)
            else ComposerAction.Error("Usage: /me action")
        }
        "msg", "query" -> {
            val target = arguments.substringBefore(' ')
            val message = arguments.substringAfter(' ', "")
            if (isValidIrcTarget(target) && message.isNotBlank()) ComposerAction.Message(target, message)
            else ComposerAction.Error("Usage: /msg nick message")
        }
        "nick" -> {
            if (isValidIrcNick(arguments)) ComposerAction.Nick(arguments)
            else ComposerAction.Error("Usage: /nick new-nick")
        }
        "help" -> ComposerAction.Error("Commands: /join, /part, /me, /msg, /nick")
        else -> ComposerAction.Error("Unknown command: /$command")
    }
}

fun isIrcMention(body: String, nick: String, caseMapping: IrcCaseMapping = IrcCaseMapping.RFC1459): Boolean {
    if (nick.isBlank()) return false
    val foldedBody = ircCaseFold(buildStyledMessage(body).text, caseMapping)
    val foldedNick = ircCaseFold(nick, caseMapping)
    var index = foldedBody.indexOf(foldedNick)
    while (index >= 0) {
        val before = foldedBody.getOrNull(index - 1)
        val after = foldedBody.getOrNull(index + foldedNick.length)
        if ((before == null || !isIrcNickCharacter(before)) && (after == null || !isIrcNickCharacter(after))) {
            return true
        }
        index = foldedBody.indexOf(foldedNick, index + 1)
    }
    return false
}

internal fun parseChannelNames(rawNames: String): List<String> =
    rawNames.split(' ')
        .map { it.trimStart('~', '&', '@', '%', '+') }
        .filter(::isValidIrcNick)

internal fun findMentionSuggestions(
    draft: String,
    channelNicknames: List<String>,
    ownNick: String,
    caseMapping: IrcCaseMapping = IrcCaseMapping.RFC1459
): List<String> {
    val prefix = draft.substringAfterLast(' ').removePrefix("@")
    if (prefix.length < 2 || prefix.any { !isIrcNickCharacter(it) }) return emptyList()
    val foldedPrefix = ircCaseFold(prefix, caseMapping)
    return channelNicknames
        .asSequence()
        .filter { !ircEquals(it, ownNick, caseMapping) }
        .filter { ircCaseFold(it, caseMapping).startsWith(foldedPrefix) }
        .sortedWith(compareBy<String> { !ircEquals(it, prefix, caseMapping) }.thenBy { ircCaseFold(it, caseMapping) })
        .take(5)
        .toList()
}

internal fun insertMentionSuggestion(draft: String, nick: String): String {
    val tokenStart = draft.lastIndexOf(' ').let { if (it == -1) 0 else it + 1 }
    return draft.substring(0, tokenStart) + "$nick "
}

private fun configTokenError(label: String, value: String): String? {
    if (value.isBlank()) return null
    return if (value.any { it.isWhitespace() || isIrcLineBreakOrControl(it) || it == ':' }) {
        "$label cannot contain whitespace, ':' or control characters"
    } else {
        null
    }
}

private fun isChatTarget(target: String): Boolean = target != "*" && classifyTarget(target) != TargetKind.SERVER

private fun isValidIrcNick(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() || isIrcLineBreakOrControl(it) || it == ':' }

private fun isIrcNickCharacter(value: Char): Boolean =
    value.isLetterOrDigit() || value in "[]\\`_^{|}-"

private const val CTCP_DELIMITER = '\u0001'

private fun isIrcLineBreakOrControl(value: Char): Boolean = value == '\r' || value == '\n' || value.code < 0x20
