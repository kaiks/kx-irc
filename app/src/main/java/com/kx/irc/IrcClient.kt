package com.kx.irc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class IrcClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicLong(0)
    private val sessionCounter = AtomicLong(0)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    private val _messages = MutableSharedFlow<IrcMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<IrcMessage> = _messages

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    private val _caseMapping = MutableStateFlow(IrcCaseMapping.RFC1459)
    val caseMapping: StateFlow<IrcCaseMapping> = _caseMapping

    @Volatile
    private var activeSession: Session? = null
    private var connectionJob: Job? = null

    fun connect(config: IrcConfig) {
        if (_status.value is ConnectionStatus.Connecting || _status.value is ConnectionStatus.Connected) return

        val session = Session(sessionCounter.incrementAndGet())
        activeSession = session
        _caseMapping.value = IrcCaseMapping.RFC1459
        _status.value = ConnectionStatus.Connecting

        connectionJob = scope.launch {
            try {
                val socket = openSocket(config)
                if (!isActive(session)) {
                    socket.close()
                    return@launch
                }
                session.attach(socket)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                session.currentNick = config.nick.ifBlank { "android" }
                startCapNegotiation(session)
                val password = config.toAuthPassword()
                if (password.isNotBlank()) writeLine(session, "PASS :$password")
                writeLine(session, "NICK ${session.currentNick}")
                writeLine(
                    session,
                    "USER ${config.username.ifBlank { "android" }} 0 * :${config.realName.ifBlank { "KX IRC" }}"
                )

                while (isActive(session) && !session.terminal) {
                    val raw = reader.readLine() ?: break
                    handleLine(raw, config, session)
                }
                if (isActive(session)) {
                    if (session.welcomed) {
                        _status.value = ConnectionStatus.Disconnected
                        _events.emit("Disconnected from ${config.host}")
                    } else {
                        failSession(session, "Server closed the connection before welcome")
                    }
                }
            } catch (_: CancellationException) {
                // disconnect() already set the terminal state for this session.
            } catch (ex: Exception) {
                if (isActive(session)) {
                    failSession(session, ex.message ?: "Connection error")
                }
            } finally {
                session.close()
                if (activeSession === session) {
                    activeSession = null
                    connectionJob = null
                }
            }
        }
    }

    private fun openSocket(config: IrcConfig): Socket {
        if (!config.useTls) return Socket(config.host, config.port)

        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(config.host, config.port) as SSLSocket
        socket.sslParameters = SSLParameters().apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
        return socket
    }

    private suspend fun handleLine(raw: String, config: IrcConfig, session: Session) {
        val parsed = parseIrcLine(raw)
        val timestamp = parseServerTime(parsed) ?: Instant.now()
        when (parsed.command.uppercase()) {
            "CAP" -> handleCap(parsed, session)
            "PING" -> writeLine(session, "PONG :${parsed.trailing ?: parsed.params.firstOrNull().orEmpty()}")
            "001" -> {
                if (!session.welcomed) {
                    session.welcomed = true
                    session.currentNick = parsed.params.firstOrNull().orEmpty().ifBlank { session.currentNick }
                    _status.value = ConnectionStatus.Connected("${config.host}:${config.port}")
                    _events.emit("Connected to ${config.host}")
                }
                config.channelList().forEach { writeLine(session, "JOIN $it") }
            }
            "005" -> {
                parseCaseMapping(parsed)?.let { _caseMapping.value = it }
                emitServerMessage("server", parsed.trailing.orEmpty().ifBlank { raw }, timestamp)
            }
            "433", "464", "465" -> {
                val reason = parsed.trailing.orEmpty().ifBlank { "Registration failed (${parsed.command})" }
                failSession(session, reason)
            }
            "PRIVMSG", "NOTICE" -> {
                val sender = parseNick(parsed.prefix)
                val target = parsed.params.firstOrNull().orEmpty()
                val resolvedTarget = resolveTarget(target, sender, session)
                val body = parsed.trailing ?: parsed.params.drop(1).joinToString(" ")
                if (ircEquals(sender, session.currentNick, _caseMapping.value) &&
                    consumePendingOutgoing(session, resolvedTarget, body)
                ) {
                    return
                }
                _messages.emit(
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
                    _messages.emit(
                        IrcMessage(
                            id = idCounter.incrementAndGet(),
                            timestamp = timestamp,
                            sender = sender,
                            target = channel,
                            body = "* $sender joined"
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
                    _messages.emit(
                        IrcMessage(
                            id = idCounter.incrementAndGet(),
                            timestamp = timestamp,
                            sender = sender,
                            target = channel,
                            body = body
                        )
                    )
                }
            }
            "MODE" -> {
                val target = parsed.params.firstOrNull().orEmpty()
                val sender = parseNick(parsed.prefix)
                val rest = (parsed.params.drop(1) + listOfNotNull(parsed.trailing)).joinToString(" ")
                val body = if (rest.isBlank()) raw else "* $sender set mode $rest"
                val resolvedTarget = if (classifyTarget(target) == TargetKind.CHANNEL) target else "server"
                emitServerMessage(resolvedTarget, body, timestamp)
            }
            "ERROR" -> failSession(session, parsed.trailing.orEmpty().ifBlank { raw })
            "QUIT" -> {
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
        scope.launch {
            val session = activeSession
            if (session == null || _status.value !is ConnectionStatus.Connected) {
                emitServerMessage("server", "Failed to send message: not connected")
                return@launch
            }
            val commands = runCatching { splitPrivmsgCommands(target, message) }
                .getOrElse {
                    emitServerMessage("server", "Failed to send message: ${it.message}")
                    return@launch
                }
            commands.forEach { command ->
                val body = command.substringAfter("PRIVMSG $target :")
                rememberPendingOutgoing(session, target, body)
                if (writeLine(session, command)) {
                    _messages.emit(
                        IrcMessage(
                            id = idCounter.incrementAndGet(),
                            timestamp = Instant.now(),
                            sender = session.currentNick,
                            target = target,
                            body = body
                        )
                    )
                } else {
                    removePendingOutgoing(session, target, body)
                    emitServerMessage("server", "Failed to send message")
                }
            }
        }
    }

    private suspend fun writeLine(session: Session, line: String): Boolean {
        if (!isActive(session) || !isSafeIrcLine(line)) return false
        return session.writeMutex.withLock {
            val current = session.writer ?: return@withLock false
            if (!isActive(session)) return@withLock false
            try {
                current.write(line)
                current.write("\r\n")
                current.flush()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun disconnect() {
        val session = activeSession
        activeSession = null
        session?.close()
        connectionJob?.cancel()
        connectionJob = null
        _status.value = ConnectionStatus.Disconnected
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    private fun isActive(session: Session): Boolean = activeSession === session

    private suspend fun failSession(session: Session, reason: String) {
        if (!isActive(session) || session.terminal) return
        session.terminal = true
        _status.value = ConnectionStatus.Failed(reason)
        _events.emit("Connection failed: $reason")
        session.close()
    }

    private fun resolveTarget(target: String, sender: String, session: Session): String {
        if (target.isBlank()) return "server"
        if (classifyTarget(target) == TargetKind.CHANNEL) return target
        if (ircEquals(target, session.currentNick, _caseMapping.value)) return sender.ifBlank { "server" }
        if (ircEquals(sender, session.currentNick, _caseMapping.value)) return target
        return sender.ifBlank { "server" }
    }

    private suspend fun emitServerMessage(target: String, body: String, timestamp: Instant = Instant.now()) {
        _messages.emit(
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
            ?: runCatching { java.time.OffsetDateTime.parse(tag).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(tag).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
    }

    private suspend fun startCapNegotiation(session: Session) {
        session.capNegotiating = true
        session.capBuffer.clear()
        writeLine(session, "CAP LS 302")
    }

    private suspend fun handleCap(line: IrcLine, session: Session) {
        if (line.params.size < 2) return
        val subcommand = line.params[1].uppercase()
        val caps = parseCapList(line)
        when (subcommand) {
            "LS" -> {
                session.capBuffer.addAll(caps)
                val isPartial = line.params.getOrNull(2) == "*"
                if (!isPartial) {
                    val requested = listOf(
                        "server-time",
                        "znc.in/server-time-iso",
                        "znc.in/server-time",
                        "echo-message"
                    ).filter { capability -> session.capBuffer.any { it.substringBefore('=').removePrefix("~") == capability } }
                    if (requested.isNotEmpty()) {
                        writeLine(session, "CAP REQ :${requested.joinToString(" ")}")
                    } else {
                        writeLine(session, "CAP END")
                        session.capNegotiating = false
                    }
                    session.capBuffer.clear()
                }
            }
            "ACK", "NAK" -> {
                if (session.capNegotiating) {
                    writeLine(session, "CAP END")
                    session.capNegotiating = false
                }
            }
        }
    }

    private fun parseCapList(line: IrcLine): List<String> {
        val rawList = line.trailing ?: line.params.drop(2).joinToString(" ")
        return rawList.split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseCaseMapping(line: IrcLine): IrcCaseMapping? {
        val mapping = line.params.drop(1)
            .firstOrNull { it.startsWith("CASEMAPPING=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.lowercase()
            ?: return null
        return when (mapping) {
            "ascii" -> IrcCaseMapping.ASCII
            "strict-rfc1459" -> IrcCaseMapping.STRICT_RFC1459
            "rfc1459" -> IrcCaseMapping.RFC1459
            else -> null
        }
    }

    private suspend fun rememberPendingOutgoing(session: Session, target: String, body: String) {
        session.outgoingMutex.withLock {
            val now = Instant.now()
            session.pendingOutgoing.removeAll { it.createdAt.plusSeconds(30).isBefore(now) }
            session.pendingOutgoing.addLast(PendingOutgoing(target, body, now))
        }
    }

    private suspend fun consumePendingOutgoing(session: Session, target: String, body: String): Boolean =
        session.outgoingMutex.withLock {
            val now = Instant.now()
            session.pendingOutgoing.removeAll { it.createdAt.plusSeconds(30).isBefore(now) }
            val matchIndex = session.pendingOutgoing.indexOfFirst {
                ircEquals(it.target, target, _caseMapping.value) && it.body == body
            }
            if (matchIndex == -1) return@withLock false
            session.pendingOutgoing.removeAt(matchIndex)
            true
        }

    private suspend fun removePendingOutgoing(session: Session, target: String, body: String) {
        session.outgoingMutex.withLock {
            val matchIndex = session.pendingOutgoing.indexOfLast {
                ircEquals(it.target, target, _caseMapping.value) && it.body == body
            }
            if (matchIndex != -1) session.pendingOutgoing.removeAt(matchIndex)
        }
    }

    private class Session(val id: Long) {
        var socket: Socket? = null
        var writer: BufferedWriter? = null
        val writeMutex = Mutex()
        val outgoingMutex = Mutex()
        val pendingOutgoing = ArrayDeque<PendingOutgoing>()
        var welcomed = false
        var terminal = false
        var currentNick = ""
        var capNegotiating = false
        val capBuffer = mutableSetOf<String>()

        fun attach(newSocket: Socket) {
            socket = newSocket
            writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))
        }

        fun close() {
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
    }

    private data class PendingOutgoing(
        val target: String,
        val body: String,
        val createdAt: Instant
    )
}
