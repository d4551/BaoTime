package com.baotime.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Owns an accepted speaker request. WebRTC owns communication mode, microphone and camera.
 *
 * API 31+ selects a communication device. API 29/30 have no such API, so the speakerphone flag
 * is used instead; both paths leave [AudioManager.getMode] and microphone mute untouched.
 */
class CallAudioRouter(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var speakerId: Int? = null
    private var legacySpeakerRequested = false

    enum class SpeakerState { Off, Switching, Active }

    fun hasSpeakerSelection(): Boolean = speakerId != null || legacySpeakerRequested

    fun enableSpeaker(): Boolean {
        val audio = manager ?: return false
        if (Build.VERSION.SDK_INT < 31) return enableLegacySpeaker(audio)
        if (speakerId != null) return true
        val speaker = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false
        if (!audio.setCommunicationDevice(speaker)) return false
        speakerId = speaker.id
        return true
    }

    fun speakerState(): SpeakerState {
        val audio = manager ?: return SpeakerState.Off
        if (Build.VERSION.SDK_INT < 31) {
            if (!legacySpeakerRequested) return SpeakerState.Off
            return if (audio.isSpeakerphoneOn) SpeakerState.Active else SpeakerState.Switching
        }
        val requested = speakerId ?: return SpeakerState.Off
        return if (audio.communicationDevice?.id == requested) SpeakerState.Active
        else SpeakerState.Switching
    }

    fun release() {
        if (!hasSpeakerSelection()) return
        speakerId = null
        val audio = manager
        if (Build.VERSION.SDK_INT >= 31) {
            audio?.clearCommunicationDevice()
        } else if (legacySpeakerRequested) {
            audio?.isSpeakerphoneOn = false
        }
        legacySpeakerRequested = false
    }

    /**
     * Pre-31 speakerphone routing. The platform flag is the only available control there; it does
     * not change communication mode or microphone mute, which stay owned by WebRTC.
     */
    private fun enableLegacySpeaker(audio: AudioManager): Boolean {
        if (legacySpeakerRequested) return true
        audio.isSpeakerphoneOn = true
        if (!audio.isSpeakerphoneOn) return false
        legacySpeakerRequested = true
        return true
    }
}
