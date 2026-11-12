package com.example.connectallspeakers

import com.example.connectallspeakers.audio.input.*
import com.example.connectallspeakers.audio.wasapi.*
import com.example.connectallspeakers.model.audio.LIVE_SAMPLE_RATE

/** Synthetic clocked PCM producer and queues. These never call native factories or devices. */
internal class LiveRig {
    var nanos = 0L
    var inputSkewPpm = 0.0
    var silentGap = false
    var closedCapture = 0
    var closedDevices = 0
    var polls = 0
    var failPacket = false
    var failOpenId: String? = null
    var cancelStart: (() -> Unit)? = null
    var beforeOpen: (() -> Unit)? = null
    var startedCapture = 0
    val endpoints = linkedMapOf<String, Endpoint>()
    private var deviceFrame = 0L
    private var captureTimeOrigin = 0L
    private var wasGap = false
    private val pcm = FloatArray(960) { if (it % 2 == 0) .25f else -.5f }
    val capture = object : LiveCaptureSource {
        override fun start() { captureTimeOrigin = nanos; deviceFrame = 0; startedCapture++ }
        override fun poll(consumer: LivePcmConsumer): Int {
            check(startedCapture > 0 && closedCapture == 0)
            polls++
            check(!failPacket) { "Capture packet failed." }
            if (silentGap) { wasGap = true; return 0 }
            if (wasGap) { deviceFrame = 0; captureTimeOrigin = nanos; wasGap = false }
            var accepted = 0
            while (deviceFrame + 480 <= (nanos - captureTimeOrigin) / 1_000_000_000.0 * LIVE_SAMPLE_RATE * (1 + inputSkewPpm / 1_000_000)) {
                val qpc = (captureTimeOrigin / 100.0 + deviceFrame * 10_000_000.0 / LIVE_SAMPLE_RATE / (1 + inputSkewPpm / 1_000_000)).toLong()
                consumer.accept(pcm, 480, deviceFrame, qpc, nanos / 100, deviceFrame == 0L, false)
                deviceFrame += 480; accepted += 480
            }
            return accepted
        }
        override fun close() { closedCapture++ }
    }
    val devices = object : PlaybackDeviceSession {
        override fun openEndpoint(endpointId: String, sampleRate: Int): PlaybackEndpoint {
            beforeOpen?.invoke()
            check(endpointId != failOpenId) { "Open failed." }
            check(sampleRate == LIVE_SAMPLE_RATE)
            return Endpoint(endpointId, if (endpointId == "a") 2400 else 4800).also { endpoints[endpointId] = it }
        }
        override fun close() { closedDevices++ }
    }
    inner class Endpoint(val id: String, override val capacityFrames: Int) : PlaybackEndpoint {
        override val channels = 2
        var running = false
        var started = 0
        var closed = 0
        var reset = 0
        var queued = 0
        var fractional = 0.0
        var skewPpm = if (id == "a") -350.0 else 250.0
        var lastBytes = byteArrayOf()
        var total = 0L
        override fun queuedFrames() = queued
        override fun submit(samples: ByteArray, frames: Int) {
            check(queued + frames <= capacityFrames)
            lastBytes = samples.copyOf(frames * 8)
            total += frames; queued += frames
        }
        override fun start() { cancelStart?.invoke(); running = true; started++ }
        override fun stop() { running = false }
        override fun reset() { queued = 0; fractional = 0.0; reset++ }
        override fun close() { closed++ }
    }
    fun advance(millis: Long = 10) {
        nanos += millis * 1_000_000
        endpoints.values.filter { it.running }.forEach {
            val elapsedFrames = millis * 48 * (1 + it.skewPpm / 1_000_000) + it.fractional
            val consumed = elapsedFrames.toInt()
            it.fractional = elapsedFrames - consumed
            it.queued = maxOf(0, it.queued - consumed)
        }
    }
}
