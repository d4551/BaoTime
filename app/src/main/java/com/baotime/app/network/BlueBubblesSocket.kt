package com.baotime.app.network

import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.InviteSource
import com.baotime.app.config.PasswordRead
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    fun onConnectError(error: String?)
    fun onCallStatusChanged(payload: String?)
    fun onLegacyIncoming(payload: String?)
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
            listener.onConnectError(args.firstOrNull()?.toString())
        }
        fun listen(event: String, receive: (String) -> Unit) {
            socket.on(event) { args ->
                when (val payload = args.firstOrNull()) {
                    is JSONObject -> receive(payload.toString())
                    is String -> receive(payload)
                }
            }
        }
        listen(IncomingFaceTimeParser.EVENT_CALL_STATUS_CHANGED, listener::onCallStatusChanged)
        listen(IncomingFaceTimeParser.EVENT_INCOMING_FACETIME, listener::onLegacyIncoming)
        return object : SocketEngine {
            override fun connect() {
                socket.connect()
            }

            override fun disconnect() {
                socket.disconnect()
            }

            override fun close() {
                socket.off()
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
    private val pingEndpoint: suspend (EndpointSnapshot) -> ServerPingResult,
    private val callLifecycle: CallLifecycle,
    private val engineFactory: SocketEngineFactory = DefaultSocketEngineFactory
) {
    constructor(
        scope: CoroutineScope,
        endpointProvider: () -> EndpointSnapshot,
        pingCheck: suspend () -> ServerPingResult,
        callLifecycle: CallLifecycle,
        engineFactory: SocketEngineFactory = DefaultSocketEngineFactory
    ) : this(scope, endpointProvider, { _ -> pingCheck() }, callLifecycle, engineFactory)

    private val lock = ReentrantLock()
    private var activeGeneration: Long = callLifecycle.configGeneration
    private var activeAttempt = 0L
    private var activeBaseUrl: String = ""
    private var activeEngine: SocketEngine? = null
    private var connectJob: Job? = null

    private val _state = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    val state: StateFlow<SocketConnectionState> = _state.asStateFlow()

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
                disconnectInternal(snapshot)
                return
            }
        }
        if (baseUrl.isEmpty() || ServerUrlPolicy.check(baseUrl) is ServerUrlPolicy.Verdict.Invalid) {
            disconnectInternal(snapshot)
            return
        }

        lock.withLock {
            if (generation < activeGeneration) return
            callLifecycle.onConfigChanged(generation, snapshot.macGeneration)
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

            val attempt = activeAttempt
            connectJob = scope.launch(start = CoroutineStart.LAZY) {
                when (pingEndpoint(snapshot)) {
                    ServerPingResult.Unauthorized -> {
                        lock.withLock {
                            if (activeAttempt == attempt) {
                                closeActiveEngineLocked()
                                _state.value = SocketConnectionState.AuthFailed(generation)
                            }
                        }
                    }
                    is ServerPingResult.Unreachable -> {
                        lock.withLock {
                            if (activeAttempt == attempt) {
                                closeActiveEngineLocked()
                                _state.value = SocketConnectionState.Offline(generation)
                            }
                        }
                    }
                    ServerPingResult.Authenticated -> {
                        startEngine(baseUrl, password, generation, attempt)
                    }
                }
            }
            connectJob?.start()
        }
    }

    /** Invalidates the socket when a newer connection is committed. */
    @JvmOverloads
    fun onConfigChanged(newGeneration: Long, macGeneration: Long? = null) {
        lock.withLock {
            callLifecycle.onConfigChanged(newGeneration, macGeneration)
            if (newGeneration <= activeGeneration) return
            closeActiveEngineLocked()
            activeGeneration = newGeneration
            _state.value = SocketConnectionState.Disconnected
        }
        ensureConnected()
    }

    private fun disconnectInternal(snapshot: EndpointSnapshot) {
        lock.withLock {
            val generation = snapshot.revision
            if (generation < activeGeneration) return
            callLifecycle.onConfigChanged(generation, snapshot.macGeneration)
            activeGeneration = generation
            closeActiveEngineLocked()
            _state.value = SocketConnectionState.Disconnected
        }
    }

    private fun startEngine(baseUrl: String, password: String, generation: Long, attempt: Long) {
        val encodedPassword = URLEncoder.encode(password, Charsets.UTF_8.name())
        val listener = object : SocketEngineListener {
            override fun onConnect() {
                lock.withLock {
                    if (activeAttempt == attempt) {
                        _state.value = SocketConnectionState.Connected(generation)
                    }
                }
            }

            override fun onDisconnect() {
                lock.withLock {
                    if (activeAttempt == attempt && _state.value !is SocketConnectionState.AuthFailed) {
                        _state.value = SocketConnectionState.Offline(generation)
                    }
                }
            }

            override fun onConnectError(error: String?) {
                val message = LogRedactor.redact(error.orEmpty())
                val isAuth = message.contains("401") || message.contains("403") ||
                    message.contains("unauthorized", ignoreCase = true)
                lock.withLock {
                    if (activeAttempt == attempt) {
                        _state.value = if (isAuth) {
                            SocketConnectionState.AuthFailed(generation)
                        } else {
                            SocketConnectionState.Offline(generation)
                        }
                    }
                }
            }

            override fun onCallStatusChanged(payload: String?) {
                val parsed = IncomingFaceTimeParser.parseCallStatusChanged(payload) ?: return
                lock.withLock {
                    if (activeAttempt != attempt) return
                    callLifecycle.onIncomingEvent(parsed, generation, InviteSource.SocketCalling)
                }
            }

            override fun onLegacyIncoming(payload: String?) {
                val parsed = IncomingFaceTimeParser.parseLegacyIncoming(payload) ?: return
                lock.withLock {
                    if (activeAttempt != attempt) return
                    callLifecycle.onIncomingEvent(parsed, generation, InviteSource.SocketLegacy)
                }
            }
        }

        lock.withLock {
            if (activeAttempt != attempt) return
            val engine = engineFactory.create(baseUrl, encodedPassword, listener)
            activeEngine = engine
            engine.connect()
        }
    }

    private fun closeActiveEngineLocked() {
        activeAttempt++
        val job = connectJob
        val engine = activeEngine
        connectJob = null
        activeEngine = null
        job?.cancel()
        engine?.close()
    }
}
