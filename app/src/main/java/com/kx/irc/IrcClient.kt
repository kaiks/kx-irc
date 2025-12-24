package com.kx.irc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory

class IrcClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicLong(0)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    private val _messages = MutableSharedFlow<IrcMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<IrcMessage> = _messages

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var welcomed = false
    private var currentNick: String = ""

    fun connect(config: IrcConfig) {
        if (_status.value is ConnectionStatus.Connecting || _status.value is ConnectionStatus.Connected) return
        _status.value = ConnectionStatus.Connecting

        scope.launch {
            try {
                val newSocket = if (config.useTls) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    factory.createSocket(config.host, config.port)
                } else {
                    Socket(config.host, config.port)
                }

                socket = newSocket
                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))

                welcomed = false
                currentNick = config.nick.ifBlank { "android" }
                val password = config.toAuthPassword()
                if (password.isNotBlank()) writeLine("PASS $password")
                writeLine("NICK $currentNick")
                writeLine("USER ${config.username.ifBlank { "android" }} 0 * :${config.realName.ifBlank { "KX IRC" }}")

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val raw = line ?: continue
                    handleLine(raw, config)
                }

                _status.value = ConnectionStatus.Disconnected
            } catch (ex: Exception) {
                _status.value = ConnectionStatus.Failed(ex.message ?: "Connection error")
                _events.tryEmit("Connection failed: ${ex.message}")
            } finally {
                closeResources()
            }
        }
    }

    private fun handleLine(raw: String, config: IrcConfig) {
        val parsed = parseIrcLine(raw)
        val timestamp = parseServerTime(parsed) ?: Instant.now()
        when (parsed.command.uppercase()) {
            "PING" -> writeLine("PONG :${parsed.trailing ?: parsed.params.firstOrNull().orEmpty()}")
            "001" -> {
                if (!welcomed) {
                    welcomed = true
                    currentNick = parsed.params.firstOrNull().orEmpty().ifBlank { currentNick }
                    _status.value = ConnectionStatus.Connected("${config.host}:${config.port}")
                    _events.tryEmit("Connected to ${config.host}")
                }
                config.channelList().forEach { writeLine("JOIN $it") }
            }
            "PRIVMSG", "NOTICE" -> {
                val sender = parseNick(parsed.prefix)
                val target = parsed.params.firstOrNull().orEmpty()
                val resolvedTarget = resolveTarget(target, sender)
                val body = parsed.trailing.orEmpty()
                _messages.tryEmit(
                    IrcMessage(
                        id = idCounter.incrementAndGet(),
                        timestamp = timestamp,
                        sender = sender,
                        target = resolvedTarget,
                        body = body,
                        isNotice = parsed.command.equals("NOTICE", ignoreCase = true)
                    )
                )
            }
            "JOIN" -> {
                val sender = parseNick(parsed.prefix)
                val channel = parsed.trailing ?: parsed.params.firstOrNull().orEmpty()
                if (channel.isNotBlank()) {
                    _messages.tryEmit(
                        IrcMessage(
                            id = idCounter.incrementAndGet(),
                            timestamp = timestamp,
                            sender = sender,
                            target = channel,
                            body = "* $sender joined",
                            isNotice = false
                        )
                    )
                }
            }
            "PART" -> {
                val sender = parseNick(parsed.prefix)
                val channel = parsed.params.firstOrNull().orEmpty()
                val reason = parsed.trailing
                if (channel.isNotBlank()) {
                    val body = if (reason.isNullOrBlank()) "* $sender left" else "* $sender left ($reason)"
                    _messages.tryEmit(
                        IrcMessage(
                            id = idCounter.incrementAndGet(),
                            timestamp = timestamp,
                            sender = sender,
                            target = channel,
                            body = body,
                            isNotice = false
                        )
                    )
                }
            }
            "MODE" -> {
                val target = parsed.params.firstOrNull().orEmpty()
                val sender = parseNick(parsed.prefix)
                val rest = (parsed.params.drop(1) + listOfNotNull(parsed.trailing)).joinToString(" ")
                val body = if (rest.isBlank()) raw else "* $sender set mode $rest"
                val resolvedTarget = if (
                    target.startsWith("#") || target.startsWith("&") || target.startsWith("+") || target.startsWith("!")
                ) target else "server"
                emitServerMessage(resolvedTarget, body, timestamp)
            }
            "ERROR", "QUIT" -> {
                val quitSender = parseNick(parsed.prefix)
                val quitReason = parsed.trailing.orEmpty().ifBlank { raw }
                emitServerMessage("server", "* $quitSender quit ($quitReason)", timestamp)
            }
            else -> {
                if (parsed.command.all { it.isDigit() }) {
                    emitServerMessage("server", parsed.trailing.orEmpty().ifBlank { raw }, timestamp)
                }
            }
        }
    }

    fun sendMessage(target: String, message: String) {
        if (target.isBlank() || target.equals("server", ignoreCase = true) || message.isBlank()) return
        scope.launch {
            val ok = writeLine("PRIVMSG $target :$message")
            if (ok) {
                _messages.tryEmit(
                    IrcMessage(
                        id = idCounter.incrementAndGet(),
                        timestamp = Instant.now(),
                        sender = "me",
                        target = target,
                        body = message,
                        isNotice = false
                    )
                )
            } else {
                emitServerMessage("server", "Failed to send message")
            }
        }
    }

    private fun writeLine(line: String): Boolean {
        val current = writer ?: return false
        return try {
            current.write(line)
            current.write("\r\n")
            current.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disconnect() {
        closeResources()
        _status.value = ConnectionStatus.Disconnected
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    private fun closeResources() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        writer = null
        socket = null
        welcomed = false
        currentNick = ""
    }

    private fun resolveTarget(target: String, sender: String): String {
        if (target.isBlank()) return "server"
        val kind = classifyTarget(target)
        if (kind == TargetKind.CHANNEL) return target
        if (target.equals(currentNick, ignoreCase = true)) {
            return sender.ifBlank { "server" }
        }
        return if (sender.isNotBlank()) sender else "server"
    }

    private fun emitServerMessage(target: String, body: String, timestamp: Instant = Instant.now()) {
        _messages.tryEmit(
            IrcMessage(
                id = idCounter.incrementAndGet(),
                timestamp = timestamp,
                sender = "server",
                target = target,
                body = body,
                isNotice = true
            )
        )
    }

    private fun parseServerTime(line: IrcLine): Instant? {
        val tag = line.tags["time"] ?: return null
        return runCatching { Instant.parse(tag) }.getOrNull()
    }
}
