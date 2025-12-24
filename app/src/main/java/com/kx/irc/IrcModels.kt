package com.kx.irc

import java.time.Instant

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data class Connected(val server: String) : ConnectionStatus()
    data class Failed(val reason: String) : ConnectionStatus()
}

data class IrcConfig(
    val host: String = "",
    val port: Int = 6697,
    val useTls: Boolean = false,
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

fun IrcConfig.validate(): String? {
    if (host.isBlank()) return "Host is required"
    if (port <= 0) return "Port must be greater than 0"
    return null
}

fun filterMessagesByTarget(messages: List<IrcMessage>, target: String): List<IrcMessage> {
    if (target.isBlank() || target == "*") return messages
    return messages.filter { it.target.equals(target, ignoreCase = true) }
}

fun classifyTarget(name: String): TargetKind {
    if (name.equals("server", ignoreCase = true)) return TargetKind.SERVER
    if (name.startsWith("#") || name.startsWith("&") || name.startsWith("+") || name.startsWith("!")) {
        return TargetKind.CHANNEL
    }
    return TargetKind.PRIVATE
}
