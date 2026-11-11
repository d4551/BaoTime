package com.baotime.app.network

import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.InviteSource
import com.baotime.app.config.PasswordRead
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder

/** Process-wide Socket.IO connection state. */
sealed class SocketConnectionState {
    data object Disconnected : SocketConnectionState()
    data class Connecting(val configGeneration: Long) : SocketConnectionState()
    data class Connected(val configGeneration: Long) : SocketConnectionState()
    data class AuthFailed(val configGeneration: Long) : SocketConnectionState()
    data class Offline(val configGeneration: Long) : SocketConnectionState()
}

/** Abstraction over the underlying Socket.IO client instance for deterministic JVM testing. */
interface SocketEngine {
    fun connect()
    fun disconnect()
    fun close()
}

/** Listener callbacks emitted by a [SocketEngine]. */
interface SocketEngineListener {
    fun onConnect()
    fun onDisconnect()
    fun onConnectError(error: Any?)
    fun onCallStatusChanged(payload: Any?)
    fun onLegacyIncoming(payload: Any?)
}

/** Factory creating one [SocketEngine] per connection configuration. */
fun interface SocketEngineFactory {
    fun create(baseUrl: String, encodedPassword: String, listener: SocketEngineListener): SocketEngine
}

/** Default production factory backed by `io.socket:socket.io-client:2.1.2` (Socket.IO v4). */
object DefaultSocketEngineFactory : SocketEngineFactory {
    override fun create(
        baseUrl: String,
        encodedPassword: String,
        listener: SocketEngineListener
    ): SocketEngine {
        val options = IO.Options().apply {
            forceNew = true
            reconnection = true
            transports = arrayOf("websocket", "polling")
            query = "guid=$encodedPassword"
        }
        val socket = IO.socket(URI.create(baseUrl), options)
        socket.on(Socket.EVENT_CONNECT) { listener.onConnect() }
        socket.on(Socket.EVENT_DISCONNECT) { listener.onDisconnect() }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            listener.onConnectError(args.firstOrNull())
        }
        socket.on(IncomingFaceTimeParser.EVENT_CALL_STATUS_CHANGED) { args ->
            listener.onCallStatusChanged(args.firstOrNull())
        }
        socket.on(IncomingFaceTimeParser.EVENT_INCOMING_FACETIME) { args ->
            listener.onLegacyIncoming(args.firstOrNull())
        }
        return object : SocketEngine {
            override fun connect() {
                socket.connect()
            }

            override fun disconnect() {
                socket.disconnect()
            }

            override fun close() {
                socket.off()
                socket.disconnect()
                socket.close()
            }
        }
    }
}

/**
 * Process-scoped Socket.IO v4 client for BlueBubbles FaceTime signaling (`socket_service.dart` @ `ff8bfd5`).
 *
 * Owned by `BaoTimeApp` (Application scope), never torn down by `MainActivity.onDestroy`.
 * Maintains at most one socket instance per [EndpointSnapshot.revision] (`configGeneration`).
 *
 * Delivery limitation: Socket.IO v4 default delivery is at-most-once
 * (`https://socket.io/docs/v4/delivery-guarantees/`). BlueBubbles `emitMessage` is a live emit with
 * no per-socket replay buffer; missed FaceTime events while disconnected are lost unless delivered
 * via NotificationListenerService (NLS) or an approved push path. Reconnect is NOT event replay.
 */
class BlueBubblesSocket(
    private val scope: CoroutineScope,
    private val endpointProvider: () -> EndpointSnapshot,
    private val pingCheck: suspend () -> ServerPingResult,
    private val callLifecycle: CallLifecycle,
    private val engineFactory: SocketEngineFactory = DefaultSocketEngineFactory
) {
    private val lock = Any()
    private var activeGeneration: Long = -1L
    private var activeBaseUrl: String = ""
    private var activeEngine: SocketEngine? = null
    private var connectJob: Job? = null

    private val _state = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    val state: StateFlow<SocketConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<IncomingFaceTimeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<IncomingFaceTimeEvent> = _events.asSharedFlow()

    /**
     * Ensures the process-wide socket is connected for the current [EndpointSnapshot.revision].
     * If `configGeneration` changed, drops the previous socket and in-flight [CallLifecycle] work
     * before reconnecting with the updated configuration.
     */
    fun ensureConnected() {
        val snapshot = endpointProvider()
        val generation = snapshot.revision
        val baseUrl = snapshot.url.trim().trimEnd('/')
        val password = when (val read = snapshot.password) {
            is PasswordRead.Available -> read.value
            PasswordRead.Missing, is PasswordRead.Unavailable -> {
                disconnectInternal(generation)
                return
            }
        }
        if (baseUrl.isEmpty() || ServerUrlPolicy.check(baseUrl) is ServerUrlPolicy.Verdict.Invalid) {
            disconnectInternal(generation)
            return
        }

        synchronized(lock) {
            if (activeGeneration != -1L && activeGeneration != generation) {
                callLifecycle.onConfigChanged(generation)
                closeActiveEngineLocked()
            }
            if (activeGeneration == generation &&
                activeBaseUrl == baseUrl &&
                ((_state.value is SocketConnectionState.Connected && activeEngine != null) ||
                    (_state.value is SocketConnectionState.Connecting &&
                        (activeEngine != null || connectJob?.isActive == true)))
            ) {
                return
            }
            closeActiveEngineLocked()
            activeGeneration = generation
            activeBaseUrl = baseUrl
            _state.value = SocketConnectionState.Connecting(generation)

            connectJob = scope.launch {
                when (pingCheck()) {
                    ServerPingResult.Unauthorized -> {
                        synchronized(lock) {
                            if (activeGeneration == generation) {
                                closeActiveEngineLocked()
                                _state.value = SocketConnectionState.AuthFailed(generation)
                            }
                        }
                    }
                    is ServerPingResult.Unreachable -> {
                        synchronized(lock) {
                            if (activeGeneration == generation) {
                                closeActiveEngineLocked()
                                _state.value = SocketConnectionState.Offline(generation)
                            }
                        }
                    }
                    ServerPingResult.Authenticated -> {
                        startEngine(baseUrl, password, generation)
                    }
                }
            }
        }
    }

    /** Called when Settings saves a new server connection. */
    fun onConfigChanged(newGeneration: Long) {
        synchronized(lock) {
            callLifecycle.onConfigChanged(newGeneration)
            closeActiveEngineLocked()
            activeGeneration = newGeneration
            _state.value = SocketConnectionState.Disconnected
        }
        ensureConnected()
    }

    /** Explicitly disconnects the socket (for process shutdown or tests; NOT called from `MainActivity.onDestroy`). */
    fun disconnect() {
        synchronized(lock) {
            closeActiveEngineLocked()
            _state.value = SocketConnectionState.Disconnected
        }
    }

    private fun disconnectInternal(generation: Long) {
        synchronized(lock) {
            if (activeGeneration != -1L && activeGeneration != generation) {
                callLifecycle.onConfigChanged(generation)
            }
            activeGeneration = generation
            closeActiveEngineLocked()
            _state.value = SocketConnectionState.Disconnected
        }
    }

    private fun startEngine(baseUrl: String, password: String, generation: Long) {
        val encodedPassword = URLEncoder.encode(password, Charsets.UTF_8.name())
        val listener = object : SocketEngineListener {
            override fun onConnect() {
                synchronized(lock) {
                    if (activeGeneration == generation) {
                        _state.value = SocketConnectionState.Connected(generation)
                    }
                }
            }

            override fun onDisconnect() {
                synchronized(lock) {
                    if (activeGeneration == generation && _state.value !is SocketConnectionState.AuthFailed) {
                        _state.value = SocketConnectionState.Offline(generation)
                    }
                }
            }

            override fun onConnectError(error: Any?) {
                val message = LogRedactor.redact(error?.toString().orEmpty())
                val isAuth = message.contains("401") || message.contains("403") ||
                    message.contains("unauthorized", ignoreCase = true)
                synchronized(lock) {
                    if (activeGeneration == generation) {
                        _state.value = if (isAuth) {
                            SocketConnectionState.AuthFailed(generation)
                        } else {
                            SocketConnectionState.Offline(generation)
                        }
                    }
                }
            }

            override fun onCallStatusChanged(payload: Any?) {
                val isCurrent = synchronized(lock) { activeGeneration == generation }
                if (!isCurrent) return
                val parsed = IncomingFaceTimeParser.parseCallStatusChanged(payload) ?: return
                _events.tryEmit(parsed)
                when (parsed.status) {
                    FaceTimeSessionStatus.INCOMING -> {
                        callLifecycle.onIncomingUuid(
                            callUuid = parsed.uuid,
                            caller = parsed.address.orEmpty(),
                            configGeneration = generation,
                            source = InviteSource.SocketCalling
                        )
                    }
                    FaceTimeSessionStatus.DISCONNECTED -> {
                        callLifecycle.onCallDisconnected(
                            callUuid = parsed.uuid,
                            configGeneration = generation
                        )
                    }
                    FaceTimeSessionStatus.UNKNOWN,
                    FaceTimeSessionStatus.ANSWERED,
                    FaceTimeSessionStatus.OUTGOING -> Unit
                }
            }

            override fun onLegacyIncoming(payload: Any?) {
                val isCurrent = synchronized(lock) { activeGeneration == generation }
                if (!isCurrent) return
                val parsed = IncomingFaceTimeParser.parseLegacyIncoming(payload) ?: return
                _events.tryEmit(parsed)
                callLifecycle.onIncomingLegacy(
                    caller = parsed.caller.orEmpty(),
                    timestampMs = parsed.timestampMs,
                    configGeneration = generation
                )
            }
        }

        synchronized(lock) {
            if (activeGeneration != generation) return
            val engine = engineFactory.create(baseUrl, encodedPassword, listener)
            activeEngine = engine
            engine.connect()
        }
    }

    private fun closeActiveEngineLocked() {
        connectJob?.cancel()
        connectJob = null
        activeEngine?.close()
        activeEngine = null
    }
}
