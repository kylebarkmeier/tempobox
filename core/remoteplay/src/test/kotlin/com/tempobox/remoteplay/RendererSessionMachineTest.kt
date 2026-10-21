package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import com.tempobox.remoteplay.RendererSessionMachine.Event
import com.tempobox.remoteplay.RendererSessionMachine.State
import org.junit.Test

class RendererSessionMachineTest {

    @Test
    fun `happy path - connect, commands, disconnect`() {
        val machine = RendererSessionMachine()
        assertThat(machine.state).isEqualTo(State.IDLE)
        assertThat(machine.on(Event.ConnectRequested)).isEqualTo(State.CONNECTING)
        assertThat(machine.on(Event.Connected)).isEqualTo(State.ACTIVE)
        assertThat(machine.on(Event.CommandSucceeded)).isEqualTo(State.ACTIVE)
        assertThat(machine.on(Event.Disconnected)).isEqualTo(State.IDLE)
    }

    @Test
    fun `one failure while connecting is fatal`() {
        val machine = RendererSessionMachine()
        machine.on(Event.ConnectRequested)
        assertThat(machine.on(Event.CommandFailed)).isEqualTo(State.FAILED)
    }

    @Test
    fun `active sessions tolerate blips below the threshold`() {
        val machine = RendererSessionMachine(failureThreshold = 3)
        machine.on(Event.ConnectRequested)
        machine.on(Event.Connected)
        assertThat(machine.on(Event.CommandFailed)).isEqualTo(State.ACTIVE)
        assertThat(machine.on(Event.CommandFailed)).isEqualTo(State.ACTIVE)
        // A success resets the count.
        assertThat(machine.on(Event.CommandSucceeded)).isEqualTo(State.ACTIVE)
        assertThat(machine.consecutiveFailures).isEqualTo(0)
        assertThat(machine.on(Event.CommandFailed)).isEqualTo(State.ACTIVE)
        assertThat(machine.on(Event.CommandFailed)).isEqualTo(State.ACTIVE)
        assertThat(machine.on(Event.CommandFailed)).isEqualTo(State.FAILED)
    }

    @Test
    fun `failed is terminal until disconnect or reconnect`() {
        val machine = RendererSessionMachine(failureThreshold = 1)
        machine.on(Event.ConnectRequested)
        machine.on(Event.Connected)
        machine.on(Event.CommandFailed)
        assertThat(machine.state).isEqualTo(State.FAILED)
        assertThat(machine.on(Event.CommandSucceeded)).isEqualTo(State.FAILED)
        assertThat(machine.on(Event.Connected)).isEqualTo(State.FAILED)
        assertThat(machine.on(Event.Disconnected)).isEqualTo(State.IDLE)
        assertThat(machine.on(Event.ConnectRequested)).isEqualTo(State.CONNECTING)
    }
}

class RendererPollingTest {

    @Test
    fun `transport states map to outcomes`() {
        assertThat(RendererPolling.outcome(false, "PLAYING", 0, 0))
            .isEqualTo(RendererPolling.Outcome.PLAYING)
        assertThat(RendererPolling.outcome(true, "TRANSITIONING", 0, 0))
            .isEqualTo(RendererPolling.Outcome.PLAYING)
        assertThat(RendererPolling.outcome(true, "PAUSED_PLAYBACK", 1000, 100_000))
            .isEqualTo(RendererPolling.Outcome.PAUSED)
        assertThat(RendererPolling.outcome(false, "STOPPED", 0, 0))
            .isEqualTo(RendererPolling.Outcome.STOPPED)
    }

    @Test
    fun `a stop near the end while playing is a track end`() {
        assertThat(RendererPolling.outcome(true, "STOPPED", 195_000, 200_000))
            .isEqualTo(RendererPolling.Outcome.TRACK_ENDED)
    }

    @Test
    fun `a stop with no usable position data while playing is a track end`() {
        assertThat(RendererPolling.outcome(true, "STOPPED", 0, 0))
            .isEqualTo(RendererPolling.Outcome.TRACK_ENDED)
    }

    @Test
    fun `a stop early in the track is the user stopping, not a track end`() {
        assertThat(RendererPolling.outcome(true, "STOPPED", 30_000, 200_000))
            .isEqualTo(RendererPolling.Outcome.STOPPED)
    }

    @Test
    fun `case-insensitive transport states`() {
        assertThat(RendererPolling.outcome(false, "playing", 0, 0))
            .isEqualTo(RendererPolling.Outcome.PLAYING)
    }
}
