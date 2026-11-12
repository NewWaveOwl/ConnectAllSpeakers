package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.audio.wasapi.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Generated distinguishable PCM only; no audio device is discovered, captured or rendered. */
class GroupStereoRoutingTest {
    private fun route(id: String = "a") = PlaybackRoute(id, SpeakerChannel.Mono, 100, mix = PlaybackMix.FullStereo)
    private fun floats(bytes: ByteArray): List<Float> = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { b -> List(bytes.size / 4) { b.float } }
    private val source = DecodedAudio("fixture.wav", 48000, 2, FloatArray(9600) { if (it % 2 == 0) .25f else -.5f })

    @Test fun stereoPassThroughMonoAverageAndMonoSourceDuplicationAreExplicit() {
        val stereo = ByteArray(16); ChannelRenderer.render(source, 0, 2, 2, route(), stereo)
        assertEquals(listOf(.25f,-.5f,.25f,-.5f), floats(stereo))
        val mono = ByteArray(8); ChannelRenderer.render(source, 0, 2, 1, route(), mono)
        assertEquals(listOf(-.125f,-.125f), floats(mono))
        val monoSource = DecodedAudio("mono", 48000, 1, floatArrayOf(.75f,-.25f))
        ChannelRenderer.render(monoSource, 0, 2, 2, route(), stereo)
        assertEquals(listOf(.75f,.75f,-.25f,-.25f), floats(stereo))
    }
    @Test fun independentFileGainsAndMuteRetainStereoAndDoNotLeakEqOrDelayTails() {
        val devices = FakePlaybackDevices()
        val eq = SpeakerEq(enabled = true, mid = EqBand(1000.0, 6.0, 1.0))
        val routes = listOf(route().copy(eq = eq, delayMillis = 1), route("b").copy(volumePercent = 50))
        val session = PlaybackRenderSession(source, devices)
        try {
            session.prepare(routes) {}
            val b = floats(devices.endpoints.getValue("b").submitted)
            assertEquals(listOf(.125f,-.25f), b.take(2))
            devices.endpoints.values.forEach { it.queued = it.capacityFrames / 2 }
            session.pump(routes.map { if (it.endpointId == "a") it.copy(muted = true) else it })
            assertTrue(floats(devices.endpoints.getValue("a").submitted).all { it == 0f })
            assertEquals(listOf(.125f,-.25f), floats(devices.endpoints.getValue("b").submitted).take(2))
        } finally { session.close() }
        assertTrue(devices.endpoints.values.all { it.closed == 1 })
    }
    @Test fun eqAndAdaptiveRateHaveNoLeftToRightCrosstalk() {
        val impulse = DecodedAudio("left impulse", 48000, 2, FloatArray(9600).apply { this[0] = .5f })
        val eq = SpeakerEq(enabled = true, mid = EqBand(1000.0, 6.0, 1.0))
        val renderer = AdaptivePcmRenderer(impulse, 2, 80, 0, OutputChannelEq(48000, eq))
        renderer.setCorrection(100.0)
        val bytes = ByteArray(640)
        var sawLeft = false
        while (!renderer.finished) {
            val frames = renderer.render(bytes, 80, route().copy(eq = eq))
            val samples = floats(bytes.copyOf(frames * 8))
            sawLeft = sawLeft || samples.filterIndexed { i,_ -> i % 2 == 0 }.any { it != 0f }
            assertTrue(samples.filterIndexed { i,_ -> i % 2 == 1 }.all { it == 0f })
        }
        assertTrue(sawLeft)
    }
    @Test fun thirtyMinuteFullMixFanOutKeepsHistoryAndRatesBoundedAndMuteIndependent() {
        val rig = LiveRig().apply { inputSkewPpm = 175.0 }
        val routes = listOf(route(), route("b").copy(volumePercent = 50))
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        var maximumHistory = 0
        try {
            session.prepare(routes) {}
            repeat(180000) { tick ->
                rig.advance(); session.pump(routes) {}
                if (tick % 1000 == 0) {
                    val status = session.status(); maximumHistory = maxOf(maximumHistory, status.bufferedFrames)
                    assertTrue(status.bufferedFrames in 0..96000)
                    assertTrue(status.correctionPpm.values.all { it.isFinite() && kotlin.math.abs(it) <= 2000 })
                }
            }
            assertEquals(listOf(.25f,-.5f), floats(rig.endpoints.getValue("a").lastBytes).take(2))
            assertEquals(listOf(.125f,-.25f), floats(rig.endpoints.getValue("b").lastBytes).take(2))
            rig.advance(); session.pump(routes.map { if (it.endpointId == "a") it.copy(muted = true) else it }) {}
            assertTrue(floats(rig.endpoints.getValue("a").lastBytes).all { it == 0f })
            assertEquals(listOf(.125f,-.25f), floats(rig.endpoints.getValue("b").lastBytes).take(2))
            println("30-minute synthetic group run: max retained history=$maximumHistory frames; ${session.status().correctionPpm}")
        } finally { session.close() }
        assertEquals(0, session.status().bufferedFrames)
        assertTrue(rig.endpoints.values.all { it.closed == 1 && it.queued == 0 })
    }
}
