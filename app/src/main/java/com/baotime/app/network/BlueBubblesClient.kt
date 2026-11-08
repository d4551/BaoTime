package com.baotime.app.network

import com.baotime.app.config.PasswordRead
import com.baotime.app.routing.BlueBubblesRoutes
import com.baotime.app.routing.UrlRouter
import com.baotime.app.config.AppConfig
import com.baotime.app.data.DeviceContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONException
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Typed reason a BlueBubbles request did not succeed. */
sealed class ServerFailure {
    data object PasswordMissing : ServerFailure()
    data object CredentialsUnavailable : ServerFailure()
    data object UrlMissing : ServerFailure()
    data object UrlRejected : ServerFailure()
    data object HttpFailed : ServerFailure()
    data object EmptyLink : ServerFailure()
}

/** Live status of the configured BlueBubbles server. */
data class BlueBubblesServerStatus(
    val reachable: Boolean,
    val serverVersion: String = "",
    val privateApiEnabled: Boolean = false,
    val helperConnected: Boolean = false,
    val detectedImessage: String = "",
    val failure: ServerFailure? = null
)

/** Result of asking the server to create a FaceTime session link. */
sealed class FaceTimeSessionResult {
    data class Created(val link: String) : FaceTimeSessionResult()
    data class Failed(val failure: ServerFailure) : FaceTimeSessionResult()
}

/** Client for the BlueBubbles server HTTP API. */
class BlueBubblesClient(private val config: AppConfig) {

    /** `GET /api/v1/server/info` with the server password query parameter. */
    suspend fun checkServerStatus(): BlueBubblesServerStatus = withContext(Dispatchers.IO) {
        runCatching { when (val endpoint = configuredEndpoint()) {
            is EndpointCheck.Rejected -> BlueBubblesServerStatus(
                reachable = false,
                failure = endpoint.failure
            )
            is EndpointCheck.Accepted -> {
                val conn = open(endpoint, BlueBubblesRoutes.SERVER_INFO, method = "GET", jsonOutput = null)
                val code = conn.responseCode
                val body = readBody(conn, code)
                if (code !in 200..299) {
                    return@withContext BlueBubblesServerStatus(
                        reachable = false,
                        failure = ServerFailure.HttpFailed
                    )
                }
                val data = JSONObject(body).optJSONObject("data")
                val version = data?.optString("server_version", "").orEmpty()
                val privateApi = data?.optionalBoolean("private_api")
                val helper = data?.optionalBoolean("helper_connected")
                val detected = data?.optionalText("detected_imessage")
                if (data == null || version.isEmpty() || privateApi == null || helper == null || detected == null) {
                    return@withContext BlueBubblesServerStatus(
                        reachable = false,
                        failure = ServerFailure.HttpFailed
                    )
                }
                BlueBubblesServerStatus(
                    reachable = true,
                    serverVersion = version,
                    privateApiEnabled = privateApi,
                    helperConnected = helper,
                    detectedImessage = detected
                )
            }
        }
        }.fold(onSuccess = { it }, onFailure = { failure ->
            BlueBubblesServerStatus(reachable = false, failure = classifyFailure(failure))
        })
    }

    /** `POST /api/v1/facetime/session` with the server password query parameter. */
    suspend fun createFaceTimeSession(): FaceTimeSessionResult = withContext(Dispatchers.IO) {
        runCatching { when (val endpoint = configuredEndpoint()) {
            is EndpointCheck.Rejected -> FaceTimeSessionResult.Failed(endpoint.failure)
            is EndpointCheck.Accepted -> {
                val conn = open(endpoint, BlueBubblesRoutes.FACETIME_SESSION, method = "POST", jsonOutput = "{}")
                val code = conn.responseCode
                val body = readBody(conn, code)
                if (code !in 200..299) {
                    return@withContext FaceTimeSessionResult.Failed(ServerFailure.HttpFailed)
                }
                val link = JSONObject(body).optJSONObject("data")?.optString("link")
                if (link.isNullOrBlank() || !UrlRouter.isFaceTimeRoomUrl(link)) {
                    FaceTimeSessionResult.Failed(ServerFailure.EmptyLink)
                } else {
                    FaceTimeSessionResult.Created(link)
                }
            }
        }
        }.fold(onSuccess = { it }, onFailure = { failure -> FaceTimeSessionResult.Failed(classifyFailure(failure)) })
    }

    /** Contact list plus whether the server actually answered (sync state). */
    data class ServerContacts(val contacts: List<DeviceContact>, val reachable: Boolean, val failure: ServerFailure? = null)

    /**
     * `GET /api/v1/contact` — the Mac's address book (the user's iMessage
     * contacts). One entry per person, phone preferred over email. Failures
     * return reachable=false so the UI can show an explicit offline state.
     */
    suspend fun fetchContacts(): ServerContacts = withContext(Dispatchers.IO) {
        runCatching {
            when (val endpoint = configuredEndpoint()) {
                is EndpointCheck.Rejected -> ServerContacts(emptyList(), reachable = false, failure = endpoint.failure)
                is EndpointCheck.Accepted -> {
                    val conn = open(endpoint, BlueBubblesRoutes.CONTACTS, method = "GET", jsonOutput = null)
                    val code = conn.responseCode
                    val body = readBody(conn, code)
                    if (code !in 200..299) {
                        return@withContext ServerContacts(emptyList(), reachable = false, failure = ServerFailure.HttpFailed)
                    }
                    val data = JSONObject(body).optJSONArray("data")
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
        }.fold(onSuccess = { it }, onFailure = { failure ->
            ServerContacts(emptyList(), false, classifyFailure(failure))
        })
    }

    private fun classifyFailure(failure: Throwable): ServerFailure = when (failure) {
        is CancellationException -> throw failure
        is IOException, is JSONException -> ServerFailure.HttpFailed
        else -> throw failure
    }

    private sealed class EndpointCheck {
        data class Accepted(val url: URL, val password: String) : EndpointCheck()
        data class Rejected(val failure: ServerFailure) : EndpointCheck()
    }

    private fun configuredEndpoint(): EndpointCheck {
        val configuration = config.connection()
        val password = when (val stored = configuration.password) {
            PasswordRead.Missing -> return EndpointCheck.Rejected(ServerFailure.PasswordMissing)
            is PasswordRead.Unavailable -> return EndpointCheck.Rejected(ServerFailure.CredentialsUnavailable)
            is PasswordRead.Available -> stored.value
        }
        val baseUrl = configuration.url
        if (baseUrl.isEmpty()) {
            return EndpointCheck.Rejected(ServerFailure.UrlMissing)
        }
        return when (ServerUrlPolicy.check(baseUrl)) {
            is ServerUrlPolicy.Verdict.Invalid -> EndpointCheck.Rejected(ServerFailure.UrlRejected)
            is ServerUrlPolicy.Verdict.Valid -> EndpointCheck.Accepted(URL(baseUrl), password)
        }
    }

    private fun open(endpoint: EndpointCheck.Accepted, path: String, method: String, jsonOutput: String?): HttpURLConnection {
        val password = URLEncoder.encode(endpoint.password, Charsets.UTF_8.name())
        val url = URL(endpoint.url, "$path?password=$password")
        val connection = url.openConnection()
        if (connection !is HttpURLConnection) {
            throw IOException("transport is not HTTP")
        }
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        if (jsonOutput != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(jsonOutput) }
        }
        return connection
    }

    private fun JSONObject.optionalBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name) || get(name) !is Boolean) return null
        return getBoolean(name)
    }

    private fun JSONObject.optionalText(name: String): String? {
        if (!has(name) || isNull(name) || get(name) !is String) return null
        val text = getString(name)
        return text.takeIf { it.isNotEmpty() }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6000
        const val READ_TIMEOUT_MS = 25000
    }
}
