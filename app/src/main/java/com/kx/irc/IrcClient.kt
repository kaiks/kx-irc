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

    private val _currentNick = MutableStateFlow("")
    val currentNick: StateFlow<String> = _currentNick

    private val _channelRosters = MutableSharedFlow<ChannelRosterUpdate>(extraBufferCapacity = 32)
    val channelRosters: SharedFlow<ChannelRosterUpdate> = _channelRosters

    @Volatile
    private var activeSession: Session? = null
    private var connectionJob: Job? = null

    @Synchronized
    fun connect(config: IrcConfig): Boolean {
        if (_status.value is ConnectionStatus.Connecting || _status.value is ConnectionStatus.Connected) return false

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
                _currentNick.value = session.currentNick
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
        return true
    }

    private fun openSocket(config: IrcConfig): Socket {
        if (!config.useTls) return Socket(config.host, config.port)

        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(config.host, config.port) as SSLSocket
        try {
            socket.sslParameters = SSLParameters().apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            socket.startHandshake()
            return socket
        } catch (exception: Exception) {
            socket.close()
            throw exception
        }
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
                    _currentNick.value = session.currentNick
                    _status.value = ConnectionStatus.Connected("${config.host}:${config.port}")
                    _events.emit("Connected to ${config.host}")
                }
                config.channelList().forEach { writeLine(session, "JOIN $it") }
            }
            "005" -> {
                parseCaseMapping(parsed)?.let { _caseMapping.value = it }
                emitServerMessage("server", parsed.trailing.orEmpty().ifBlank { raw }, timestamp)
            }
            "353" -> {
                val channel = parsed.params.lastOrNull().orEmpty()
                if (classifyTarget(channel) == TargetKind.CHANNEL) {
                    if (session.namesInProgress.add(channel)) session.channelMembers[channel] = mutableListOf()
                    val roster = session.channelMembers.getOrPut(channel) { mutableListOf() }
                    parseChannelMembers(parsed.trailing.orEmpty()).forEach { incoming ->
                        val index = roster.indexOfFirst { ircEquals(it.nick, incoming.nick, _caseMapping.value) }
                        if (index == -1) roster.add(incoming) else roster[index] = incoming
                    }
                }
            }
            "366" -> {
                val channel = parsed.params.lastOrNull().orEmpty()
                if (session.namesInProgress.remove(channel)) emitRoster(session, channel)
            }
            "433", "464", "465" -> {
                val reason = parsed.trailing.orEmpty().ifBlank { "Registration failed (${parsed.command})" }
                failSession(session, reason)
            }
            "PRIVMSG", "NOTICE" -> {
                val sender = parseNick(parsed.prefix)
                val target = parsed.params.firstOrNull().orEmpty()
                val resolvedTarget = resolveTarget(target, sender, session)
                val rawBody = parsed.trailing ?: parsed.params.drop(1).joinToString(" ")
                if (ircEquals(sender, session.currentNick, _caseMapping.value) &&
                    consumePendingOutgoing(session, resolvedTarget, rawBody)
                ) {
                    return
                }
                val action = parseCtcpAction(rawBody)
                _messages.emit(
                    IrcMessage(
                        id = idCounter.incrementAndGet(),
                        timestamp = timestamp,
                        sender = sender,
                        target = resolvedTarget,
                        body = action ?: rawBody,
                        isNotice = parsed.command.equals("NOTICE", ignoreCase = true),
                        isAction = action != null
                    )
                )
            }
            "NICK" -> {
                val sender = parseNick(parsed.prefix)
                val newNick = parsed.trailing ?: parsed.params.firstOrNull().orEmpty()
                if (newNick.isNotBlank() && ircEquals(sender, session.currentNick, _caseMapping.value)) {
                    session.currentNick = newNick
                    _currentNick.value = newNick
                }
                renameRosterMember(session, sender, newNick)
                emitServerMessage("server", "* $sender is now known as $newNick", timestamp)
            }
            "JOIN" -> {
                val sender = parseNick(parsed.prefix)
                val channel = parsed.trailing ?: parsed.params.firstOrNull().orEmpty()
                if (channel.isNotBlank()) {
                    addRosterMember(session, channel, sender)
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
                    removeRosterMember(session, channel, sender)
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
                val roster = session.channelMembers[target]
                val modeString = parsed.params.getOrNull(1).orEmpty()
                val modeArguments = parsed.params.drop(2) + listOfNotNull(parsed.trailing)
                if (roster != null && applyChannelMemberModes(roster, modeString, modeArguments, _caseMapping.value)) {
                    emitRoster(session, target)
                }
                emitServerMessage(resolvedTarget, body, timestamp)
            }
            "ERROR" -> failSession(session, parsed.trailing.orEmpty().ifBlank { raw })
            "QUIT" -> {
                val quitSender = parseNick(parsed.prefix)
                val quitReason = parsed.trailing.orEmpty().ifBlank { raw }
                removeRosterMemberEverywhere(session, quitSender)
                emitServerMessage("server", "* $quitSender quit ($quitReason)", timestamp)
            }
            "KICK" -> {
                val channel = parsed.params.firstOrNull().orEmpty()
                val kickedNick = parsed.params.getOrNull(1).orEmpty()
                removeRosterMember(session, channel, kickedNick)
                emitServerMessage(channel.ifBlank { "server" }, "* $kickedNick was kicked", timestamp)
            }
            else -> {
                if (parsed.command.all { it.isDigit() }) {
                    emitServerMessage("server", parsed.trailing.orEmpty().ifBlank { raw }, timestamp)
                }
            }
        }
    }

    fun sendMessage(target: String, message: String) {
        sendPrivmsg(target, message, isAction = false)
    }

    fun sendAction(target: String, action: String) {
        sendPrivmsg(target, action, isAction = true)
    }

    private fun sendPrivmsg(target: String, message: String, isAction: Boolean) {
        scope.launch {
            val session = activeSession
            if (session == null || _status.value !is ConnectionStatus.Connected) {
                emitServerMessage("server", "Failed to send message: not connected")
                return@launch
            }
            val bodies = runCatching {
                splitPrivmsgBodies(target, message, reservedBytes = if (isAction) CTCP_ACTION_OVERHEAD_BYTES else 0)
            }
                .getOrElse {
                    emitServerMessage("server", "Failed to send message: ${it.message}")
                    return@launch
                }
            bodies.forEach { body ->
                val rawBody = if (isAction) "\u0001ACTION $body\u0001" else body
                val command = "PRIVMSG $target :$rawBody"
                rememberPendingOutgoing(session, target, rawBody)
                if (writeLine(session, command)) {
                    _messages.emit(
                        IrcMessage(
                            id = idCounter.incrementAndGet(),
                            timestamp = Instant.now(),
                            sender = session.currentNick,
                            target = target,
                            body = body,
                            isAction = isAction
                        )
                    )
                } else {
                    removePendingOutgoing(session, target, rawBody)
                    emitServerMessage("server", "Failed to send message")
                }
            }
        }
    }

    fun joinChannel(channel: String) {
        sendCommand("JOIN $channel", "Failed to join $channel")
    }

    fun partChannel(channel: String, reason: String?) {
        val command = if (reason.isNullOrBlank()) "PART $channel" else "PART $channel :$reason"
        sendCommand(command, "Failed to leave $channel")
    }

    fun changeNick(nick: String) {
        sendCommand("NICK $nick", "Failed to change nickname")
    }

    fun kick(channel: String, nick: String) {
        sendCommand("KICK $channel $nick", "Failed to kick $nick")
    }

    fun setChannelMemberMode(channel: String, nick: String, mode: Char) {
        if (mode !in "ov") return
        sendCommand("MODE $channel +$mode $nick", "Failed to set mode +$mode on $nick")
    }

    private fun sendCommand(command: String, failureMessage: String) {
        scope.launch {
            val session = activeSession
            if (session == null || _status.value !is ConnectionStatus.Connected || !writeLine(session, command)) {
                emitServerMessage("server", failureMessage)
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

    @Synchronized
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
                        "echo-message",
                        "multi-prefix"
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

    private fun parseCtcpAction(body: String): String? {
        if (!body.startsWith(CTCP_DELIMITER) || !body.endsWith(CTCP_DELIMITER)) return null
        val action = body.removePrefix("${CTCP_DELIMITER}ACTION ").removeSuffix(CTCP_DELIMITER.toString())
        return action.takeIf { body == "${CTCP_DELIMITER}ACTION $it$CTCP_DELIMITER" && it.isNotBlank() }
    }

    private suspend fun addRosterMember(session: Session, channel: String, nick: String) {
        if (channel.isBlank() || nick.isBlank()) return
        val roster = session.channelMembers.getOrPut(channel) { mutableListOf() }
        val existingIndex = roster.indexOfFirst { ircEquals(it.nick, nick, _caseMapping.value) }
        if (existingIndex == -1) roster.add(ChannelMember(nick))
        emitRoster(session, channel)
    }

    private suspend fun removeRosterMember(session: Session, channel: String, nick: String) {
        val roster = session.channelMembers[channel] ?: return
        val removed = roster.removeAll { ircEquals(it.nick, nick, _caseMapping.value) }
        if (removed) emitRoster(session, channel)
    }

    private suspend fun removeRosterMemberEverywhere(session: Session, nick: String) {
        session.channelMembers.keys.toList().forEach { channel -> removeRosterMember(session, channel, nick) }
    }

    private suspend fun renameRosterMember(session: Session, oldNick: String, newNick: String) {
        if (newNick.isBlank()) return
        session.channelMembers.keys.toList().forEach { channel ->
            val roster = session.channelMembers[channel] ?: return@forEach
            val existingIndex = roster.indexOfFirst { ircEquals(it.nick, oldNick, _caseMapping.value) }
            if (existingIndex == -1) return@forEach
            roster[existingIndex] = roster[existingIndex].copy(nick = newNick)
            emitRoster(session, channel)
        }
    }

    private suspend fun emitRoster(session: Session, channel: String) {
        val roster = session.channelMembers[channel] ?: return
        _channelRosters.emit(
            ChannelRosterUpdate(
                channel,
                roster.sortedWith(
                    compareByDescending<ChannelMember> { it.isOperator }
                        .thenByDescending { it.isVoiced }
                        .thenBy { ircCaseFold(it.nick, _caseMapping.value) }
                )
            )
        )
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
        val channelMembers = mutableMapOf<String, MutableList<ChannelMember>>()
        val namesInProgress = mutableSetOf<String>()

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

    private companion object {
        const val CTCP_DELIMITER = '\u0001'
        const val CTCP_ACTION_OVERHEAD_BYTES = 9
    }
}
