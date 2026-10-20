package com.tempobox.playback

import android.media.AudioManager

/**
 * Decides when automatic playback must NOT happen because a call is active.
 *
 * Car head units misbehave around calls: answering one can re-trigger
 * Bluetooth connect events and AVRCP PLAY commands, which would start music
 * in the background of the call. Audio mode (no permission needed) tells us a
 * call is up; phone-state listening would need READ_PHONE_STATE.
 */
object CallGuard {

    /** True while a cellular or VoIP call is ringing or active. */
    fun isCallMode(audioMode: Int): Boolean = when (audioMode) {
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        -> true
        else -> false
    }

    /**
     * Whether the Bluetooth start-on-connect autoplay may fire. Checked both
     * when the connect event arrives and again after the route-settling delay,
     * because the connect event may itself be the car switching to call audio.
     */
    fun shouldAutoplay(
        startOnConnect: Boolean,
        isConnectEvent: Boolean,
        hasQueue: Boolean,
        audioMode: Int,
    ): Boolean = startOnConnect && isConnectEvent && hasQueue && !isCallMode(audioMode)

    /**
     * Whether playback that just started should be suppressed. Give the audio
     * mode [RECHECK_DELAY_MS] to settle first: when a call ends, focus can
     * return a beat before the mode drops back to NORMAL, and pausing then
     * would kill the legitimate auto-resume.
     *
     * Deliberately narrower than [isCallMode]: only a cellular call
     * (MODE_IN_CALL) qualifies. During VoIP calls (MODE_IN_COMMUNICATION,
     * which apps can hold for hours) the user must stay able to start music
     * on purpose, and VoIP apps take permanent audio focus anyway, which
     * already pauses us without resuming.
     */
    fun shouldPauseNow(isPlaying: Boolean, audioMode: Int): Boolean =
        isPlaying && audioMode == AudioManager.MODE_IN_CALL

    /** Delay before re-checking the mode and pausing mid-call playback. */
    const val RECHECK_DELAY_MS = 1_200L
}
