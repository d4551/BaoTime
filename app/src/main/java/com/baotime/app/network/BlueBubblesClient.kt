package com.baotime.app.network

import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordRead
import com.baotime.app.data.DeviceContact
import com.baotime.app.routing.BlueBubblesRoutes
import com.baotime.app.routing.UrlRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Client for the BlueBubbles server HTTP API. */
class BlueBubblesClient(
    private val endpointProvider: () -> EndpointSnapshot,
    httpClient: OkHttpClient? = null
) {
    constructor(config: AppConfig) : this(endpointProvider = {
        val conn = config.connection()
        EndpointSnapshot(conn.url, conn.password, conn.revision, conn.macGeneration)
    })

    private val statusLock = ReentrantLock()
    private val statusRequests = AtomicLong()
    private val completedStatus = MutableStateFlow<ServerStatusObservation?>(null)
    internal val serverStatuses = completedStatus.asStateFlow()

    internal data class ServerStatusObservation(
        val revision: Long,
        val url: String,
        val request: Long,
        val status: BlueBubblesServerStatus,
        val capability: BlueBubblesServerStatus?
    )

    /** Retains the last redacted request URL for diagnostic verification without secret leakage. */
    @Volatile
    var lastRedactedRequestUrl: String = ""
        private set

    private val redactionInterceptor = Interceptor { chain ->
        val request = chain.request()
        lastRedactedRequestUrl = LogRedactor.redact(request.url.toString())
        chain.proceed(request)
    }

    private val client: OkHttpClient = (httpClient ?: defaultOkHttpClient())
        .newBuilder().addInterceptor(redactionInterceptor).build()

    private val answerClient: OkHttpClient = client.newBuilder()
        .readTimeout(ANSWER_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    /** `GET /api/v1/ping` with the server password query parameter. */
    suspend fun pingServer(snapshot: EndpointSnapshot? = null): ServerPingResult = withContext(Dispatchers.IO) {
        runCatching {
            when (val endpoint = configuredEndpoint(snapshot = snapshot)) {
                is EndpointCheck.Rejected -> ServerPingResult.Unreachable(endpoint.failure)
                is EndpointCheck.Accepted -> {
                    val response = execute(client, endpoint, BlueBubblesRoutes.PING, "GET", null)
                    when (response.code) {
                        401, 403 -> ServerPingResult.Unauthorized
                        in 200..299 -> {
                            if (JSONObject(response.body).optString("data", "") == "pong") ServerPingResult.Authenticated
                            else ServerPingResult.Unreachable(ServerFailure.HttpFailed)
                        }
                        else -> ServerPingResult.Unreachable(ServerFailure.HttpFailed)
                    }
                }
            }
        }.fold(onSuccess = { it }, onFailure = { ServerPingResult.Unreachable(classifyFailure(it)) })
    }

    /** `GET /api/v1/server/info` with the server password query parameter. */
    suspend fun checkServerStatus(): BlueBubblesServerStatus {
        val request = statusRequests.incrementAndGet()
        return withContext(Dispatchers.IO) {
            val configuration = runCatching { endpointProvider() }.getOrElse { failure ->
                return@withContext BlueBubblesServerStatus(reachable = false, failure = classifyFailure(failure))
            }
            val endpoint = configuredEndpoint(snapshot = configuration)
            val url = when (endpoint) {
                is EndpointCheck.Accepted -> endpoint.url.toString()
                is EndpointCheck.Rejected -> configuration.url.trim().trimEnd('/')
            }
            observeServerStatus(configuration.revision, url, request) {
                when (endpoint) {
                    is EndpointCheck.Rejected -> BlueBubblesServerStatus(reachable = false, failure = endpoint.failure)
                    is EndpointCheck.Accepted -> readServerStatus(endpoint)
                }
            }?.status ?: BlueBubblesServerStatus(reachable = false, failure = ServerFailure.ConnectionChanged)
        }
    }

    private suspend fun observeServerStatus(
        revision: Long,
        url: String,
        request: Long,
        read: suspend () -> BlueBubblesServerStatus
    ): ServerStatusObservation? {
        val status = runCatching { read() }.getOrElse { failure ->
            BlueBubblesServerStatus(reachable = false, failure = classifyFailure(failure))
        }
        val coroutineContext = currentCoroutineContext()
        return statusLock.withLock {
            coroutineContext.ensureActive()
            val previous = completedStatus.value
            val matching = previous?.takeIf { it.revision == revision && it.url == url }
            if (previous != null && (previous.revision > revision ||
                    (previous.revision == revision && previous.request > request))) {
                return@withLock matching
            }
            val capability = if (status.reachable || status.failure == ServerFailure.Unauthorized) status
                else matching?.capability
            ServerStatusObservation(revision, url, request, status, capability).also { completedStatus.value = it }
        }
    }

    private suspend fun readServerStatus(endpoint: EndpointCheck.Accepted): BlueBubblesServerStatus {
        val response = execute(client, endpoint, BlueBubblesRoutes.SERVER_INFO, "GET", null)
        if (response.code == 401 || response.code == 403) {
            return BlueBubblesServerStatus(reachable = false, failure = ServerFailure.Unauthorized)
        }
        if (response.code !in 200..299) {
            return BlueBubblesServerStatus(reachable = false, failure = ServerFailure.HttpFailed)
        }
        val data = JSONObject(response.body).optJSONObject("data")
        val version = data?.optString("server_version", "").orEmpty()
        val privateApi = data?.optionalBoolean("private_api")
        val helper = data?.optionalBoolean("helper_connected")
        val detected = data?.optionalText("detected_imessage")
        if (data == null || version.isEmpty() || privateApi == null || helper == null || detected == null) {
            return BlueBubblesServerStatus(reachable = false, failure = ServerFailure.HttpFailed)
        }
        val tempStatus = BlueBubblesServerStatus(
            reachable = true,
            serverVersion = version,
            privateApiEnabled = privateApi,
            helperConnected = helper,
            detectedImessage = detected
        )
        return tempStatus.copy(failure = ServerCapability.verifyFaceTimeCapability(tempStatus))
    }

    /** `POST /api/v1/facetime/session` with the server password query parameter. */
    @JvmOverloads
    suspend fun createFaceTimeSession(expectedRevision: Long? = null): FaceTimeSessionResult = withContext(Dispatchers.IO) {
        runCatching {
            when (val endpoint = configuredEndpoint(expectedRevision)) {
                is EndpointCheck.Rejected -> FaceTimeSessionResult.Failed(endpoint.failure)
                is EndpointCheck.Accepted -> {
                    ensureCapable(endpoint)?.let { return@withContext FaceTimeSessionResult.Failed(it) }
                    val response = execute(client, endpoint, BlueBubblesRoutes.FACETIME_SESSION, "POST", "{}")
                    parseSessionLinkResponse(response)
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> FaceTimeSessionResult.Failed(classifyFailure(failure)) }
        )
    }

    /** `POST /api/v1/facetime/answer/:call_uuid` with `{}` body (`FaceTimeApi.answer` @ `ff8bfd5`). */
    @JvmOverloads
    suspend fun answerFaceTimeCall(callUuid: String, expectedRevision: Long? = null): FaceTimeSessionResult = withContext(Dispatchers.IO) {
        val trimmed = callUuid.trim()
        if (trimmed.isEmpty()) return@withContext FaceTimeSessionResult.Failed(ServerFailure.HttpFailed)
        runCatching {
            when (val endpoint = configuredEndpoint(expectedRevision)) {
                is EndpointCheck.Rejected -> FaceTimeSessionResult.Failed(endpoint.failure)
                is EndpointCheck.Accepted -> {
                    ensureCapable(endpoint)?.let { return@withContext FaceTimeSessionResult.Failed(it) }
                    val response = execute(answerClient, endpoint, BlueBubblesRoutes.facetimeAnswer(trimmed), "POST", "{}")
                    parseSessionLinkResponse(response)
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> FaceTimeSessionResult.Failed(classifyFailure(failure)) }
        )
    }

    /** `POST /api/v1/facetime/leave/:call_uuid` best-effort (`facetimeRouter.ts` @ `76d2b2e`). */
    @JvmOverloads
    suspend fun leaveFaceTimeCall(callUuid: String, expectedRevision: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val trimmed = callUuid.trim()
        if (trimmed.isEmpty()) return@withContext false
        runCatching {
            when (val endpoint = configuredEndpoint(expectedRevision)) {
                is EndpointCheck.Rejected -> false
                is EndpointCheck.Accepted -> {
                    ensureCapable(endpoint)?.let { return@withContext false }
                    val response = execute(client, endpoint, BlueBubblesRoutes.facetimeLeave(trimmed), "POST", "{}")
                    response.code in 200..299
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> fallbackOnHttpFailure(failure, false) }
        )
    }

    /**
     * `GET /api/v1/handle/availability/facetime?address=` advisory check (`facetimeRouter.ts` @ `76d2b2e`).
     * Never blocks generic `POST /api/v1/facetime/session`.
     */
    suspend fun checkFaceTimeAvailability(address: String, expectedRevision: Long? = null): FaceTimeAvailabilityResult = withContext(Dispatchers.IO) {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || UrlRouter.wholeFaceTimeUrl(trimmed) != null) {
            return@withContext FaceTimeAvailabilityResult.Indeterminate
        }
        runCatching {
            when (val endpoint = configuredEndpoint(expectedRevision)) {
                is EndpointCheck.Rejected -> FaceTimeAvailabilityResult.Indeterminate
                is EndpointCheck.Accepted -> {
                    val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
                    val response = execute(
                        client, endpoint, BlueBubblesRoutes.FACETIME_AVAILABILITY, "GET", null, extraQuery = "address=$encoded"
                    )
                    if (response.code !in 200..299) return@withContext FaceTimeAvailabilityResult.Indeterminate
                    val available = JSONObject(response.body).optJSONObject("data")?.optionalBoolean("available")
                        ?: return@withContext FaceTimeAvailabilityResult.Indeterminate
                    if (available) FaceTimeAvailabilityResult.Available else FaceTimeAvailabilityResult.Unavailable
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> fallbackOnHttpFailure(failure, FaceTimeAvailabilityResult.Indeterminate) }
        )
    }

    /** `POST /api/v1/fcm/device` registers a Firebase device identifier. */
    suspend fun registerPushDevice(deviceName: String, identifier: String): Boolean = withContext(Dispatchers.IO) {
        val cleanName = deviceName.trim()
        val cleanId = identifier.trim()
        if (cleanName.isEmpty() || cleanId.isEmpty()) return@withContext false
        runCatching {
            when (val endpoint = configuredEndpoint()) {
                is EndpointCheck.Rejected -> false
                is EndpointCheck.Accepted -> {
                    val payload = JSONObject()
                        .put("name", cleanName)
                        .put("identifier", cleanId)
                        .toString()
                    val response = execute(client, endpoint, BlueBubblesRoutes.FCM_DEVICE, "POST", payload)
                    response.code in 200..299
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> fallbackOnHttpFailure(failure, false) }
        )
    }

    /** Contact list plus whether the server actually answered (sync state). */
    data class ServerContacts(val contacts: List<DeviceContact>, val reachable: Boolean, val failure: ServerFailure? = null)

    /**
     * `GET /api/v1/contact` — the Mac's address book (the user's iMessage
     * contacts). One entry per person, phone preferred over email. Failures
     * return reachable=false so the UI can show an explicit offline state.
     */
    suspend fun fetchContacts(expectedRevision: Long? = null): ServerContacts = withContext(Dispatchers.IO) {
        runCatching {
            when (val endpoint = configuredEndpoint(expectedRevision)) {
                is EndpointCheck.Rejected -> ServerContacts(emptyList(), reachable = false, failure = endpoint.failure)
                is EndpointCheck.Accepted -> {
                    val response = execute(client, endpoint, BlueBubblesRoutes.CONTACTS, "GET", null)
                    if (response.code == 401 || response.code == 403) {
                        return@withContext ServerContacts(emptyList(), reachable = false, failure = ServerFailure.Unauthorized)
                    }
                    if (response.code !in 200..299) {
                        return@withContext ServerContacts(emptyList(), reachable = false, failure = ServerFailure.HttpFailed)
                    }
                    val data = JSONObject(response.body).optJSONArray("data")
                        ?: return@withContext ServerContacts(emptyList(), reachable = false, failure = ServerFailure.HttpFailed)
                    val contacts = mutableListOf<DeviceContact>()
                    for (i in 0 until data.length()) {
                        val person = data.optJSONObject(i) ?: continue
                        val name = person.optString("displayName").ifBlank {
                            listOf(person.optString("firstName"), person.optString("lastName"))
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                        }
                        val phone = person.optJSONArray("phoneNumbers")
                            ?.optJSONObject(0)?.optString("address")?.takeIf { it.isNotBlank() }
                        val email = person.optJSONArray("emails")
                            ?.optJSONObject(0)?.optString("address")?.takeIf { it.isNotBlank() }
                        val address = phone ?: email ?: continue
                        contacts.add(
                            DeviceContact(
                                id = person.optString("id", address),
                                displayName = name.ifBlank { address },
                                primaryAddress = address,
                                isEmail = phone == null
                            )
                        )
                    }
                    ServerContacts(contacts, reachable = true)
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> ServerContacts(emptyList(), false, classifyFailure(failure)) }
        )
    }

    private suspend fun ensureCapable(endpoint: EndpointCheck.Accepted): ServerFailure? {
        val url = endpoint.url.toString()
        val existing = completedStatus.value?.takeIf { it.revision == endpoint.revision && it.url == url }?.capability
        if (existing != null) return ServerCapability.verifyFaceTimeCapability(existing)
        val observation = observeServerStatus(endpoint.revision, url, statusRequests.incrementAndGet()) {
            readServerStatus(endpoint)
        } ?: return ServerFailure.ConnectionChanged
        return ServerCapability.verifyFaceTimeCapability(observation.capability ?: observation.status)
    }

    private fun parseSessionLinkResponse(response: RawHttpResponse): FaceTimeSessionResult {
        if (response.code == 401 || response.code == 403) return FaceTimeSessionResult.Failed(ServerFailure.Unauthorized)
        if (response.code !in 200..299) return FaceTimeSessionResult.Failed(ServerFailure.HttpFailed)
        val link = JSONObject(response.body).optJSONObject("data")?.optString("link")
        return if (link.isNullOrBlank() || !UrlRouter.isFaceTimeRoomUrl(link)) {
            FaceTimeSessionResult.Failed(ServerFailure.EmptyLink)
        } else {
            FaceTimeSessionResult.Created(link)
        }
    }

    private fun classifyFailure(failure: Throwable): ServerFailure = when (failure) {
        is CancellationException -> throw failure
        is IOException, is JSONException -> ServerFailure.HttpFailed
        else -> throw failure
    }

    private fun <T> fallbackOnHttpFailure(failure: Throwable, fallback: T): T = when (failure) {
        is CancellationException -> throw failure
        is IOException, is JSONException -> fallback
        else -> throw failure
    }

    private sealed class EndpointCheck {
        data class Accepted(val url: URL, val password: String, val revision: Long) : EndpointCheck()
        data class Rejected(val failure: ServerFailure) : EndpointCheck()
    }

    private fun configuredEndpoint(expectedRevision: Long? = null, snapshot: EndpointSnapshot? = null): EndpointCheck {
        val configuration = snapshot ?: endpointProvider()
        if (expectedRevision != null && configuration.revision != expectedRevision) {
            return EndpointCheck.Rejected(ServerFailure.ConnectionChanged)
        }
        val password = when (val stored = configuration.password) {
            PasswordRead.Missing -> return EndpointCheck.Rejected(ServerFailure.PasswordMissing)
            is PasswordRead.Unavailable -> return EndpointCheck.Rejected(ServerFailure.CredentialsUnavailable)
            is PasswordRead.Available -> stored.value
        }
        val baseUrl = configuration.url.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return EndpointCheck.Rejected(ServerFailure.UrlMissing)
        return when (ServerUrlPolicy.check(baseUrl)) {
            is ServerUrlPolicy.Verdict.Invalid -> EndpointCheck.Rejected(ServerFailure.UrlRejected)
            is ServerUrlPolicy.Verdict.Valid -> EndpointCheck.Accepted(URL(baseUrl), password, configuration.revision)
        }
    }

    private suspend fun execute(
        httpClient: OkHttpClient,
        endpoint: EndpointCheck.Accepted,
        path: String,
        method: String,
        jsonOutput: String?,
        extraQuery: String? = null
    ): RawHttpResponse = BlueBubblesHttpTransport.execute(
        httpClient = httpClient,
        baseUrl = endpoint.url,
        password = endpoint.password,
        path = path,
        method = method,
        jsonOutput = jsonOutput,
        extraQuery = extraQuery
    )

    private fun JSONObject.optionalBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name) || get(name) !is Boolean) return null
        return getBoolean(name)
    }

    private fun JSONObject.optionalText(name: String): String? {
        if (!has(name) || isNull(name) || get(name) !is String) return null
        val text = getString(name)
        return text.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 6_000L
        const val READ_TIMEOUT_MS = 25_000L
        const val ANSWER_READ_TIMEOUT_MS = 45_000L

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
