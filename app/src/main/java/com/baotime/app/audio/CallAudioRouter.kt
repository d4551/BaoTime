package com.baotime.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Owns an accepted speaker request. WebRTC owns communication mode, microphone and camera. */
class CallAudioRouter(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var speakerId: Int? = null

    enum class SpeakerState { Off, Switching, Active }

    fun hasSpeakerSelection(): Boolean = speakerId != null

    fun enableSpeaker(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        if (speakerId != null) return true
        val audio = manager ?: return false
        val speaker = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false
        if (!audio.setCommunicationDevice(speaker)) return false
        speakerId = speaker.id
        return true
    }

    fun speakerState(): SpeakerState {
        if (Build.VERSION.SDK_INT < 31) return SpeakerState.Off
        val requested = speakerId ?: return SpeakerState.Off
        return if (manager?.communicationDevice?.id == requested) SpeakerState.Active
        else SpeakerState.Switching
    }

    fun release() {
        if (speakerId == null) return
        speakerId = null
        if (Build.VERSION.SDK_INT >= 31) manager?.clearCommunicationDevice()
    }
}
