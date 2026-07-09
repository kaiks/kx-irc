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
    val isNotice: Boolean = false
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

internal fun splitPrivmsgCommands(target: String, message: String): List<String> {
    require(isValidIrcTarget(target)) { "Invalid IRC target" }
    require(message.isNotBlank()) { "Message is required" }
    require(message.none(::isIrcLineBreakOrControl)) { "Messages cannot contain line breaks or control characters" }

    val prefix = "PRIVMSG $target :"
    val availableBytes = IRC_MAX_LINE_BYTES - "\r\n".toByteArray(Charsets.UTF_8).size -
        prefix.toByteArray(Charsets.UTF_8).size
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
    return chunks.map { "$prefix$it" }
}

internal fun isValidIrcTarget(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() || isIrcLineBreakOrControl(it) || it == ':' || it == ',' }

internal fun isSafeIrcLine(line: String): Boolean =
    line.none(::isIrcLineBreakOrControl) && "${line}\r\n".toByteArray(Charsets.UTF_8).size <= IRC_MAX_LINE_BYTES

private fun configTokenError(label: String, value: String): String? {
    if (value.isBlank()) return null
    return if (value.any { it.isWhitespace() || isIrcLineBreakOrControl(it) || it == ':' }) {
        "$label cannot contain whitespace, ':' or control characters"
    } else {
        null
    }
}

private fun isIrcLineBreakOrControl(value: Char): Boolean = value == '\r' || value == '\n' || value.code < 0x20
