package com.baotime.app

import android.app.Application
import android.os.Build
import com.baotime.app.call.CallInvite
import com.baotime.app.call.CallLifecycle
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.BlueBubblesSocket
import com.baotime.app.network.EndpointSnapshot
import com.baotime.app.network.ServerFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Application owner for process-wide FaceTime signaling state ([CallLifecycle] and [BlueBubblesSocket]).
 *
 * Keeping [callLifecycle] and [socket] in the Application process ensures `MainActivity.onDestroy`
 * only unbinds UI and never drops an active Socket.IO connection or in-flight answer/leave call.
 */
class BaoTimeApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appConfig: AppConfig by lazy { AppConfig(this) }
    val callHistory: CallHistory by lazy { CallHistory(this) }
    val contactsRepository: ContactsRepository by lazy { ContactsRepository(this) }
    val bbClient: BlueBubblesClient by lazy { BlueBubblesClient(appConfig) }

    private val _historyUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val historyUpdated: SharedFlow<Unit> = _historyUpdated.asSharedFlow()

    private val _answerFailures = MutableSharedFlow<ServerFailure>(extraBufferCapacity = 8)
    val answerFailures: SharedFlow<ServerFailure> = _answerFailures.asSharedFlow()

    val callLifecycle: CallLifecycle by lazy {
        CallLifecycle(
            scope = applicationScope,
            initialConfigGeneration = appConfig.connectionRevision(),
            answerCall = { callUuid -> bbClient.answerFaceTimeCall(callUuid) },
            leaveCall = { callUuid -> bbClient.leaveFaceTimeCall(callUuid) },
            onMissedInvite = { invite, _ -> recordMissedCall(invite) },
            onAnswerFailed = { _, failure -> _answerFailures.tryEmit(failure) }
        )
    }

    val socket: BlueBubblesSocket by lazy {
        BlueBubblesSocket(
            scope = applicationScope,
            endpointProvider = {
                val conn = appConfig.connection()
                EndpointSnapshot(conn.url, conn.password, conn.revision)
            },
            pingCheck = { bbClient.pingServer() },
            callLifecycle = callLifecycle
        )
    }

    /** Registers a UnifiedPush or FCM endpoint token with the configured BlueBubbles server (`Task 11`). */
    fun onPushEndpointRegistered(endpoint: String) {
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "BaoTime Android" }
        applicationScope.launch {
            bbClient.registerPushDevice(deviceName, endpoint)
        }
    }

    private suspend fun recordMissedCall(invite: CallInvite) {
        val rawCaller = invite.caller.ifBlank { getString(R.string.default_call_label) }
        val resolvedName = contactsRepository.lookupContactName(rawCaller) ?: rawCaller
        callHistory.recordCall(
            handleOrNumber = rawCaller,
            displayName = resolvedName,
            faceTimeUrl = invite.faceTimeUrl,
            callType = BaoCallRecord.TYPE_MISSED
        )
        _historyUpdated.tryEmit(Unit)
    }
}
