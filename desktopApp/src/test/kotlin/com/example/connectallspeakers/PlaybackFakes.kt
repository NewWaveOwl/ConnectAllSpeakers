package com.example.connectallspeakers

import com.example.connectallspeakers.audio.wasapi.PlaybackDeviceSession
import com.example.connectallspeakers.audio.wasapi.PlaybackEndpoint
import com.example.connectallspeakers.data.audio.AudioOutputRepository
import com.example.connectallspeakers.data.audio.AudioOutputSnapshot
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.model.audio.AudioOutputDevice
import kotlinx.coroutines.flow.MutableStateFlow

/** Test-only endpoints; no native implementation is constructed by playback tests. */
internal class FakePlaybackDevices : PlaybackDeviceSession {
    val events = mutableListOf<String>()
    val endpoints = mutableMapOf<String, FakePlaybackEndpoint>()
    var failOpen: String? = null
    var closed = 0
    var beforeOpen: (() -> Unit)? = null
    override fun openEndpoint(endpointId: String, sampleRate: Int): PlaybackEndpoint {
        events += "open:$endpointId"
        beforeOpen?.invoke()
        check(endpointId != failOpen) { "Output initialization failed." }
        return FakePlaybackEndpoint(endpointId, events).also { endpoints[endpointId] = it }
    }
    override fun close() { closed++; events += "close:devices" }
}

internal class FakePlaybackEndpoint(private val id: String, private val events: MutableList<String>) : PlaybackEndpoint {
    override val channels = 2
    override val capacityFrames = 80
    var queued = 0
    var closed = 0
    var failStart = false
    var failStop = false
    var submitted = byteArrayOf()
    var started = false
    override fun queuedFrames() = queued
    override fun submit(samples: ByteArray, frames: Int) {
        check(queued + frames <= capacityFrames)
        submitted = samples.copyOf(frames * channels * 4)
        queued += frames
        events += "submit:$id"
    }
    override fun start() { events += "start:$id"; check(!failStart) { "Start failed." }; started = true }
    override fun stop() { events += "stop:$id"; started = false; check(!failStop) { "Stop failed." } }
    override fun reset() { events += "reset:$id"; queued = 0 }
    override fun close() { events += "close:$id"; closed++ }
}

internal class FakePlaybackOutputs : AudioOutputRepository {
    override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
        listOf(AudioOutputDevice("a", "Same name"), AudioOutputDevice("b", "Same name"))))
    override fun refresh() = Unit
}
