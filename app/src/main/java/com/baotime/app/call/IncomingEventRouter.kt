package com.baotime.app.call

import com.baotime.app.network.FaceTimeSessionStatus
import com.baotime.app.network.IncomingFaceTimeEvent

/**
 * Single owner of the parsed FaceTime event switch. [com.baotime.app.network.BlueBubblesSocket] and
 * [IncomingPushReceiver] parse their transport payloads and enter through [CallLifecycle.onIncomingEvent];
 * no transport re-implements the INCOMING/DISCONNECTED/legacy mapping.
 */
internal class IncomingEventRouter(private val lifecycle: CallLifecycle) {

    /** Routes one parsed event; statuses the server never acts on return false without state changes. */
    fun onIncomingEvent(
        event: IncomingFaceTimeEvent,
        configGeneration: Long,
        source: InviteSource,
        uiEnabled: Boolean
    ): Boolean = when (event) {
        is IncomingFaceTimeEvent.CallStatusChanged -> when (event.status) {
            FaceTimeSessionStatus.INCOMING -> lifecycle.onIncomingUuid(
                callUuid = event.uuid,
                caller = event.address.orEmpty(),
                configGeneration = configGeneration,
                source = source
            )
            FaceTimeSessionStatus.DISCONNECTED -> lifecycle.onCallDisconnected(
                callUuid = event.uuid,
                configGeneration = configGeneration
            )
            FaceTimeSessionStatus.UNKNOWN,
            FaceTimeSessionStatus.ANSWERED,
            FaceTimeSessionStatus.OUTGOING -> false
        }
        // Legacy intake keeps the SocketLegacy identity on every transport so socket/push
        // duplicates of the same caller+timestamp collapse in InviteIntake.
        is IncomingFaceTimeEvent.LegacyIncoming -> lifecycle.onIncomingLegacy(
            caller = event.caller.orEmpty(),
            timestampMs = event.timestampMs,
            configGeneration = configGeneration,
            uiEnabled = uiEnabled
        )
    }
}