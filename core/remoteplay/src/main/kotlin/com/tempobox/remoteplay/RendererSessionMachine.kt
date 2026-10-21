package com.tempobox.remoteplay

/**
 * Pure state machine for one renderer session. [RemotePlayController] feeds it
 * command and poll outcomes; it decides when a flaky renderer counts as dead
 * (consecutive failures reach [failureThreshold]) so the controller can fall
 * back to local playback exactly once, not on every blip.
 */
class RendererSessionMachine(private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD) {

    enum class State { IDLE, CONNECTING, ACTIVE, FAILED }

    sealed interface Event {
        /** connect() started: device resolved, server starting. */
        data object ConnectRequested : Event

        /** First command on the renderer succeeded. */
        data object Connected : Event

        /** A control action or poll round-trip succeeded. */
        data object CommandSucceeded : Event

        /** A control action or poll round-trip failed (network or SOAP fault). */
        data object CommandFailed : Event

        /** disconnect() or shutdown: back to idle, whatever the state. */
        data object Disconnected : Event
    }

    var state: State = State.IDLE
        private set

    var consecutiveFailures: Int = 0
        private set

    /**
     * Applies [event]; returns the resulting state. Synchronized because
     * commands and the poll loop report from different coroutines.
     */
    @Synchronized
    fun on(event: Event): State {
        state = when (event) {
            Event.ConnectRequested -> {
                consecutiveFailures = 0
                State.CONNECTING
            }
            Event.Connected -> when (state) {
                State.CONNECTING -> {
                    consecutiveFailures = 0
                    State.ACTIVE
                }
                else -> state
            }
            Event.CommandSucceeded -> {
                consecutiveFailures = 0
                state
            }
            Event.CommandFailed -> when (state) {
                // While connecting, one failure is conclusive: the renderer
                // never answered at all.
                State.CONNECTING -> State.FAILED
                State.ACTIVE -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= failureThreshold) State.FAILED else State.ACTIVE
                }
                else -> state
            }
            Event.Disconnected -> {
                consecutiveFailures = 0
                State.IDLE
            }
        }
        return state
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
    }
}

/**
 * Interprets one transport-state poll. Pure so the end-of-track heuristics
 * (renderers report STOPPED both when a track finishes and when someone stops
 * them at the device) are unit-testable.
 */
object RendererPolling {

    enum class Outcome { PLAYING, PAUSED, STOPPED, TRACK_ENDED }

    /**
     * @param wasPlaying        whether the previous poll (or command) had the renderer playing
     * @param transportState    AVTransport CurrentTransportState
     * @param lastPositionMs    last position reported while playing
     * @param durationMs        track duration (0 when unknown)
     */
    fun outcome(
        wasPlaying: Boolean,
        transportState: String,
        lastPositionMs: Long,
        durationMs: Long,
    ): Outcome = when (transportState.uppercase()) {
        "PLAYING", "TRANSITIONING" -> Outcome.PLAYING
        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> Outcome.PAUSED
        else -> {
            // STOPPED / NO_MEDIA_PRESENT. Natural end: we were playing and the
            // last seen position was near the end (or the renderer never
            // reported usable position/duration). A stop early in the track
            // was someone pressing Stop on the device: do not auto-advance.
            val nearEnd = durationMs <= 0 ||
                lastPositionMs <= 0 ||
                lastPositionMs >= durationMs - END_SLACK_MS
            if (wasPlaying && nearEnd) Outcome.TRACK_ENDED else Outcome.STOPPED
        }
    }

    const val END_SLACK_MS = 10_000L
}
