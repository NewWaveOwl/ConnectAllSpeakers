package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.OutputEqProcessor
import com.example.connectallspeakers.model.audio.*
import kotlin.math.*
import kotlin.test.*

/** Generated signals only; response expectations use the independent BLT analog transfer magnitude. */
class OutputEqProcessorTest {
    @Test fun neutralAndBypassedAreBitExactAtEverySupportedRepresentativeRate() {
        val inputs = floatArrayOf(-1f, -.7f, -.0f, 0f, .001f, .6f, 1f)
        for (rate in rates) for (eq in listOf(SpeakerEq(), SpeakerEq(enabled = true),
            SpeakerEq(low = EqBand(16000.0, 12.0, .3), high = EqBand(40.0, -12.0, 2.0)))) {
            val processor = OutputEqProcessor(rate, eq)
            repeat(100) { inputs.forEach { assertEquals(it.toRawBits(), processor.process(it).toRawBits()) } }
            assertEquals(0, processor.tailFrames)
            assertEquals(0, processor.snapshot().limitedFrames)
        }
    }

    @Test fun impulseResponsesMatchBellCurvesGainFrequencyAndQBoundsAcrossSourceRates() {
        for (rate in rates) for (center in listOf(40.0, 1000.0, maximumEqFrequency(rate)))
            for (gain in listOf(-12.0, 12.0)) for (q in listOf(.3, 2.0)) {
                val band = EqBand(center, gain, q)
                val settings = SpeakerEq(enabled = true, mid = band)
                val processor = OutputEqProcessor(rate, settings)
                val impulse = FloatArray(rate / 2) { processor.process(if (it == 0) 1f else 0f) }
                for (frequency in listOf(0.0, center * .5, center, minOf(rate * .49, center * 1.5), rate * .49)) {
                    val actual = response(impulse, frequency, rate)
                    val expected = analogMagnitude(band, frequency, rate) * 10.0.pow(-settings.headroomDb / 20)
                    val errorDb = 20 * log10(actual / expected)
                    assertTrue(abs(errorDb) < .03, "$rate Hz center=$center gain=$gain Q=$q at $frequency Hz: $errorDb dB error")
                }
                assertEquals(0, processor.snapshot().limitedFrames)
            }
    }

    @Test fun sineGainsAndThreeOverlappingBoostsFollowDeclaredHeadroomWithoutClipping() {
        val rate = 48_000
        val eq = SpeakerEq(true, EqBand(1000.0, 12.0), EqBand(1000.0, 12.0), EqBand(1000.0, 12.0))
        val processor = OutputEqProcessor(rate, eq)
        var inputPower = 0.0; var outputPower = 0.0
        repeat(rate) { frame ->
            val input = (.7 * sin(2 * PI * 1000 * frame / rate)).toFloat()
            val output = processor.process(input)
            assertTrue(output.isFinite() && output in -1f..1f)
            if (frame > rate / 2) { inputPower += input * input; outputPower += output * output }
        }
        assertEquals(36.0, processor.snapshot().headroomDb)
        assertEquals(1.0, sqrt(outputPower / inputPower), .00001)
        assertEquals(0, processor.snapshot().limitedFrames)
    }

    @Test fun rapidChangesCoalesceAndSettleWithoutResettingAnInFlightCrossfade() {
        val processor = OutputEqProcessor(8000)
        var previous = .1f
        var maximumJump = 0f
        repeat(16_000) { frame ->
            if (frame % 8 == 0) {
                val even = frame % 16 == 0
                assertTrue(processor.request(SpeakerEq(even, EqBand(if (even) 40.0 else 3600.0,
                    if (even) 12.0 else -12.0, if (even) .3 else 2.0), mid = EqBand(1000.0, -12.0))))
            }
            val value = processor.process(.1f)
            assertTrue(value.isFinite() && value in -1f..1f)
            maximumJump = maxOf(maximumJump, abs(value - previous)); previous = value
        }
        assertTrue(maximumJump < .01f, "DC parameter-change jump $maximumJump exceeds 0.01 FS")
        val final = SpeakerEq(true, EqBand(40.0, 6.0), EqBand(1000.0, -6.0), EqBand(3000.0, 12.0))
        processor.request(final)
        repeat(8000) { previous = processor.process(.1f) }
        assertFalse(processor.snapshot().transitioning)
        assertEquals((.1 * 10.0.pow(-18.0 / 20)).toFloat(), previous, .000001f)
        processor.request(SpeakerEq())
        repeat(1600) { processor.process(.1f) }
        assertEquals(.1f.toRawBits(), processor.process(.1f).toRawBits())
    }

    @Test fun rejectedRateUpdatePreservesLiveAndPendingSafeFilters() {
        val initial = SpeakerEq(true, mid = EqBand(1000.0, -6.0))
        val actual = OutputEqProcessor(8000, initial)
        val control = OutputEqProcessor(8000, initial)
        val next = initial.copy(low = EqBand(100.0, 4.0))
        actual.request(next); control.request(next)
        repeat(100) { assertEquals(control.process(.1f), actual.process(.1f)) }
        val pending = next.copy(high = EqBand(3000.0, -12.0))
        actual.request(pending); control.request(pending)
        assertFalse(actual.request(pending.copy(high = EqBand(16000.0, 12.0))))
        assertTrue(actual.snapshot().message!!.contains("3600 Hz"))
        repeat(3000) { frame ->
            val input = (.1 * sin(2 * PI * 600 * frame / 8000)).toFloat()
            assertEquals(control.process(input).toRawBits(), actual.process(input).toRawBits())
        }
        assertTrue(actual.request(pending))
        assertNull(actual.snapshot().message)
    }

    @Test fun guardCountsNonFiniteAndOverRangeFramesAndTailIsBoundedWithFreshRestartState() {
        val neutral = OutputEqProcessor(8000)
        assertEquals(0f, neutral.process(Float.NaN))
        assertEquals(0f, neutral.process(Float.POSITIVE_INFINITY))
        assertEquals(1f, neutral.process(2f)); assertEquals(-1f, neutral.process(-2f))
        assertEquals(4, neutral.snapshot().limitedFrames)
        val eq = SpeakerEq(true, low = EqBand(40.0, 12.0, 2.0))
        val processor = OutputEqProcessor(8000, eq)
        processor.process(.5f)
        assertEquals(4000, processor.remainingTailFrames)
        repeat(4000) { assertTrue(processor.process(0f, endOfSource = true).isFinite()) }
        assertEquals(0, processor.remainingTailFrames)
        assertEquals(0f, processor.process(0f, endOfSource = true))
        val fresh = OutputEqProcessor(8000, eq)
        assertEquals(0f, fresh.process(0f))
        assertFailsWith<IllegalArgumentException> { OutputEqProcessor(8000, eq.copy(high = EqBand(4000.0))) }
    }

    /** @param impulse Generated finite impulse response. @param frequency Probe Hz. @param rate Source rate.
     * @return Magnitude of its numerical Fourier transform, independent of filter coefficients.
     */
    private fun response(impulse: FloatArray, frequency: Double, rate: Int): Double {
        var real = 0.0; var imaginary = 0.0
        impulse.forEachIndexed { index, value ->
            val angle = 2 * PI * frequency * index / rate
            real += value * cos(angle); imaginary -= value * sin(angle)
        }
        return hypot(real, imaginary)
    }

    /** Analog bell transfer evaluated under the bilinear frequency mapping, not normalized digital coefficients.
     * @param band Declared bell. @param frequency Probe Hz. @param rate Source rate. @return Expected gain.
     */
    private fun analogMagnitude(band: EqBand, frequency: Double, rate: Int): Double {
        val ratio = tan(PI * frequency / rate) / tan(PI * band.frequencyHz / rate)
        val amplitude = 10.0.pow(band.gainDb / 40)
        val real = 1 - ratio * ratio
        return hypot(real, ratio * amplitude / band.q) / hypot(real, ratio / (amplitude * band.q))
    }

    private val rates = listOf(8000, 11025, 16000, 22050, 44100, 48000, 96000, 192000)
}
