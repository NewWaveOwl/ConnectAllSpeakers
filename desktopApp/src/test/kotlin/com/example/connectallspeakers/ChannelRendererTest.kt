package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackRoute
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.*

class ChannelRendererTest {
    private val stereo = DecodedAudio("known", 8_000, 2, floatArrayOf(1f, -.5f, -.25f, .75f))
    private fun render(channel: SpeakerChannel, volume: Int = 100, channels: Int = 2,
        source: DecodedAudio = stereo): List<Float> {
        val bytes = ByteArray(source.frames * channels * 4)
        ChannelRenderer.render(source, 0, source.frames, channels, PlaybackRoute("a", channel, volume), bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 4) { buffer.float }
    }

    @Test fun leftIsDuplicatedAcrossPhysicalChannels() {
        assertEquals(listOf(1f, 1f, -.25f, -.25f), render(SpeakerChannel.Left))
    }
    @Test fun rightUsesRightSourceAndLinearGain() {
        assertEquals(listOf(-.25f, -.25f, .375f, .375f), render(SpeakerChannel.Right, 50))
    }
    @Test fun monoAveragesWithoutDoublingOrClipping() {
        assertEquals(listOf(.25f, .25f, .25f, .25f), render(SpeakerChannel.Mono))
    }
    @Test fun zeroVolumeAndMonoEndpointAreSupported() {
        assertTrue(render(SpeakerChannel.Left, 0, 1).all { abs(it) == 0f })
        assertEquals(listOf(-.5f, .75f), render(SpeakerChannel.Right, 100, 1))
    }
    @Test fun monoSourcePlaysOnEitherAssignment() {
        val mono = DecodedAudio("mono", 48_000, 1, floatArrayOf(.5f, -.5f))
        SpeakerChannel.entries.forEach { assertEquals(listOf(.5f, .5f, -.5f, -.5f), render(it, source = mono)) }
    }
    @Test fun toneIsBoundedRampedAndExactlyOneSecond() {
        val tone = speakerTestTone()
        assertEquals(48_000, tone.frames)
        assertEquals(1_000L, tone.description.durationMillis)
        assertTrue(tone.samples.all { it.isFinite() && abs(it) <= .100001f })
        assertEquals(0f, tone.samples.first())
        assertEquals(0f, abs(tone.samples.last()))
    }
    @Test fun malformedRouteAndFrameRangeAreRejected() {
        assertFailsWith<IllegalArgumentException> { PlaybackRoute("", SpeakerChannel.Left, 80) }
        assertFailsWith<IllegalArgumentException> { PlaybackRoute("a", SpeakerChannel.Left, 101) }
        assertFailsWith<IllegalArgumentException> { ChannelRenderer.render(stereo, 1, 2, 2,
            PlaybackRoute("a", SpeakerChannel.Left, 80), ByteArray(16)) }
    }
}
