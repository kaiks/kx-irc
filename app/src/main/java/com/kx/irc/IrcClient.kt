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
                val password = config.toAuthPassword()
                if (password.isNotBlank()) sendRaw("PASS $password")
                sendRaw("NICK ${config.nick.ifBlank { "android" }}")
                sendRaw("USER ${config.username.ifBlank { "android" }} 0 * :${config.realName.ifBlank { "KX IRC" }}")

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
        when (parsed.command.uppercase()) {
            "PING" -> sendRaw("PONG :${parsed.trailing ?: parsed.params.firstOrNull().orEmpty()}")
            "001" -> {
                if (!welcomed) {
                    welcomed = true
                    _status.value = ConnectionStatus.Connected("${config.host}:${config.port}")
                    _events.tryEmit("Connected to ${config.host}")
                }
                config.channelList().forEach { sendRaw("JOIN $it") }
            }
            "PRIVMSG", "NOTICE" -> {
                val sender = parseNick(parsed.prefix)
                val target = parsed.params.firstOrNull().orEmpty()
                val body = parsed.trailing.orEmpty()
                _messages.tryEmit(
                    IrcMessage(
                        id = idCounter.incrementAndGet(),
                        timestamp = Instant.now(),
                        sender = sender,
                        target = target,
                        body = body,
                        isNotice = parsed.command.equals("NOTICE", ignoreCase = true)
                    )
                )
            }
        }
    }

    fun sendMessage(target: String, message: String) {
        if (target.isBlank() || message.isBlank()) return
        sendRaw("PRIVMSG $target :$message")
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
    }

    fun sendRaw(line: String) {
        try {
            writer?.apply {
                write(line)
                write("\r\n")
                flush()
            }
        } catch (_: Exception) {
            // Ignore send errors; reader loop will handle disconnects.
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
    }
}
