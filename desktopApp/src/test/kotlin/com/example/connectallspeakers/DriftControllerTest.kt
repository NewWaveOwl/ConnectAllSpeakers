package com.example.connectallspeakers

import com.example.connectallspeakers.audio.wasapi.*
import com.example.connectallspeakers.model.audio.*
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Closed-loop virtual clocks: 200 ms packets, no wall-clock waits or native endpoints. */
class DriftControllerTest {
    private fun observation(clockMillis: Double, sourceMillis: Double, elapsedMillis: Double,
        qpcLagMillis: Double = 0.0): DriftObservation {
        val qpc = 100_000_000L + ((elapsedMillis - qpcLagMillis) * 10_000).toLong()
        val clock = OutputClockTiming(((clockMillis - qpcLagMillis) * 1_000).toLong(), 1_000_000,
            qpc, clockMillis - qpcLagMillis)
        return DriftObservation(clock, sourceMillis - qpcLagMillis, qpc + 1_000)
    }

    @Test fun positiveAndNegativeClockDifferencesConvergeWithinOneMillisecondForTwentyVirtualMinutes() {
        for (rate in listOf(-500.0, -200.0, -50.0, 50.0, 200.0, 500.0)) {
            val servo = DriftController("reference", 8_000)
            var sourceB = 877.0 // Explicit 123 ms initial/manual offset is retained.
            var worstSettledSkew = 0.0
            var lastCorrection = 0.0
            var minimumCorrection = 0.0
            var maximumCorrection = 0.0
            var lateMinimum = Double.POSITIVE_INFINITY
            var lateMaximum = Double.NEGATIVE_INFINITY
            for (packet in 0..6_000) {
                val time = packet * 200.0
                if (packet > 0) sourceB += 200 * (1 + rate / 1_000_000) * (1 + servo.correctionPpm / 1_000_000)
                val correction = servo.observe(observation(1000 + time, 1000 + time, time),
                    observation(1000 + time * (1 + rate / 1_000_000), sourceB, time), (time * 1_000_000).toLong())
                assertTrue(abs(correction) <= MAX_DRIFT_CORRECTION_PPM)
                assertTrue(abs(correction - lastCorrection) <= 20.00001)
                lastCorrection = correction
                minimumCorrection = minOf(minimumCorrection, correction); maximumCorrection = maxOf(maximumCorrection, correction)
                if (time >= 1_140_000) { lateMinimum = minOf(lateMinimum, correction); lateMaximum = maxOf(lateMaximum, correction) }
                val state = servo.snapshot(false)
                if (time >= 120_000) worstSettledSkew = maxOf(worstSettledSkew,
                    abs(sourceB - (1000 + time) - state.initialEngineOffsetMillis!!))
            }
            assertTrue(worstSettledSkew <= 1.0, "$rate ppm: $worstSettledSkew ms")
            assertTrue(lateMaximum - lateMinimum < .01, "$rate ppm: correction did not settle: $lateMinimum..$lateMaximum")
            assertEquals(DriftPhase.Tracking, servo.snapshot(false).phase)
            assertTrue(servo.snapshot(false).initialEngineOffsetMillis!! in -125.0..-121.0)
            if (rate > 0) assertTrue(minimumCorrection < -rate * .8 && maximumCorrection <= 0)
            else assertTrue(maximumCorrection > -rate * .8 && minimumCorrection >= 0)
            println("Virtual 20-minute $rate ppm: max residual after 120 s = $worstSettledSkew ms; correction $minimumCorrection..$maximumCorrection ppm")
        }
    }

    @Test fun neutralClocksAndSmallAlternatingJitterDoNotRequestCorrection() {
        for (jitter in listOf(0.0, .15)) {
            val servo = DriftController("reference", 48_000)
            repeat(900) { packet ->
                val time = packet * 200.0
                val noise = if (packet % 2 == 0) jitter else -jitter
                servo.observe(observation(1000 + time, 1000 + time, time),
                    observation(1000 + time + noise, 877 + time + noise, time), packet * 200_000_000L)
                assertEquals(0.0, servo.correctionPpm)
            }
            assertEquals(DriftPhase.Tracking, servo.snapshot(false).phase)
        }
    }

    @Test fun jitteredClockWithDifferentQpcTimestampsStillConvergesWithoutAbruptCorrections() {
        val servo = DriftController("reference", 44_100)
        var sourceB = 900.0
        var last = 0.0
        repeat(1_500) { packet ->
            val time = packet * 200.0
            if (packet > 0) sourceB += 200 * 1.0002 * (1 + servo.correctionPpm / 1_000_000)
            val noise = .1 * sin(packet * .7)
            servo.observe(observation(1000 + time, 1000 + time, time, .3),
                observation(1000 + time * 1.0002 + noise, sourceB + noise, time), packet * 200_000_000L)
            assertTrue(abs(servo.correctionPpm - last) <= 20.00001)
            last = servo.correctionPpm
        }
        assertTrue(abs(servo.snapshot(false).skewMillis!!) < 1.0)
        assertTrue(servo.snapshot(false).uncertaintyMillis!! < 1.0)
    }

    @Test fun unavailableStaleAndTransientOutliersHoldBrieflyAndPersistentLossStops() {
        val servo = DriftController("reference", 48_000)
        repeat(60) { packet ->
            val time = packet * 200.0
            servo.observe(observation(1000 + time, 1000 + time, time), observation(1000 + time, 900 + time, time), packet * 200_000_000L)
        }
        val unknown = DriftObservation(null, null, null)
        servo.observe(unknown, unknown, 12_000_000_000)
        assertEquals(DriftPhase.Degraded, servo.snapshot(false).phase)
        assertNull(servo.snapshot(false).relativeRatePpm)
        servo.observe(observation(13_400.0, 13_400.0, 12_400.0), observation(13_400.0, 13_300.0, 12_400.0), 12_400_000_000)
        assertEquals(DriftPhase.Tracking, servo.snapshot(false).phase)
        servo.observe(unknown, unknown, 12_600_000_000)
        assertFailsWith<IllegalStateException> { servo.observe(unknown, unknown, 14_600_000_001) }
        assertEquals(DriftPhase.Failed, servo.snapshot(false).phase)
    }

    @Test fun backwardClockDiscontinuityAndExcessiveRelativeRateStopRatherThanSkip() {
        val servo = DriftController("a", 48_000)
        val valid = observation(1000.0, 1000.0, 0.0)
        assertFailsWith<IllegalStateException> { servo.observe(valid.copy(discontinuity = true), valid, 0) }
        val excessive = DriftController("a", 48_000)
        assertFailsWith<IllegalStateException> {
            repeat(100) { packet ->
                val time = packet * 200.0
                excessive.observe(observation(1000 + time, 1000 + time, time),
                    observation(1000 + time * 1.001, 900 + time * 1.001, time), packet * 200_000_000L)
            }
        }
        assertEquals(DriftPhase.Failed, excessive.snapshot(false).phase)
    }

    @Test fun pauseRebaselineRetainsOnlyTheBoundedRateAndExcludesPausedTime() {
        val servo = DriftController("a", 48_000)
        var source = 900.0
        repeat(300) { packet ->
            val time = packet * 200.0
            if (packet > 0) source += 200 * 1.0002 * (1 + servo.correctionPpm / 1_000_000)
            servo.observe(observation(1000 + time, 1000 + time, time),
                observation(1000 + time * 1.0002, source, time), packet * 200_000_000L)
        }
        val held = servo.correctionPpm
        assertTrue(held < 0)
        servo.pause()
        assertEquals(DriftPhase.Paused, servo.snapshot(false).phase)
        assertNull(servo.snapshot(false).skewMillis)
        servo.rebaseline()
        servo.observe(observation(61_000.0, 61_000.0, 3_660_000.0),
            observation(61_012.0, source, 3_660_000.0), 3_660_000_000_000)
        assertEquals(held, servo.correctionPpm)
        assertEquals(DriftPhase.WarmingUp, servo.snapshot(false).phase)
        assertNull(servo.snapshot(false).initialEngineOffsetMillis)
    }

    @Test fun transientOutlierAndDuplicateTimestampAreIgnoredAndFreshSamplesRecover() {
        val servo = DriftController("a", 48_000)
        repeat(60) { packet ->
            val time = packet * 200.0
            servo.observe(observation(1000 + time, 1000 + time, time), observation(1000 + time, 900 + time, time), packet * 200_000_000L)
        }
        val held = servo.correctionPpm
        servo.observe(observation(13_000.0, 13_000.0, 12_000.0), observation(13_005.0, 12_905.0, 12_000.0), 12_000_000_000)
        assertEquals(DriftPhase.Degraded, servo.snapshot(false).phase)
        assertEquals(held, servo.correctionPpm)
        val a = observation(13_200.0, 13_200.0, 12_200.0)
        val b = observation(13_200.0, 13_100.0, 12_200.0)
        servo.observe(a, b, 12_200_000_000)
        assertEquals(DriftPhase.Tracking, servo.snapshot(false).phase)
        servo.observe(a, b, 12_201_000_000)
        assertEquals(DriftPhase.Degraded, servo.snapshot(false).phase)
        assertEquals(held, servo.correctionPpm)
        servo.observe(observation(13_400.0, 13_400.0, 12_400.0), observation(13_400.0, 13_300.0, 12_400.0), 12_400_000_000)
        assertEquals(DriftPhase.Tracking, servo.snapshot(false).phase)
    }

    @Test fun staleOrFutureQpcDoesNotEnterRegressionAndLargeAccumulatedSkewStops() {
        val servo = DriftController("a", 8_000)
        val a = observation(1000.0, 1000.0, 0.0)
        servo.observe(a, a.copy(observedQpc100Nanos = a.clock!!.qpcPosition100Nanos - 1), 0)
        assertEquals(DriftPhase.Degraded, servo.snapshot(false).phase)
        servo.observe(a, a.copy(observedQpc100Nanos = a.clock.qpcPosition100Nanos + 500_001), 200_000_000)
        assertEquals(0.0, servo.correctionPpm)
        repeat(60) { packet ->
            val time = packet * 200.0
            servo.observe(observation(1000 + time, 1000 + time, time), observation(1000 + time, 900 + time, time), packet * 200_000_000L)
        }
        assertFailsWith<IllegalStateException> {
            servo.observe(observation(13_000.0, 13_000.0, 12_000.0), observation(13_000.0, 12_950.0, 12_000.0), 12_000_000_000)
        }
        assertEquals(DriftPhase.Failed, servo.snapshot(false).phase)
    }

    @Test fun persistentCorrectionDemandIsCappedThenFailsWithoutAnUnboundedIntegrator() {
        val servo = DriftController("a", 48_000)
        var limited = false
        val failure = assertFailsWith<IllegalStateException> {
            repeat(200) { packet ->
                val time = packet * 200.0
                val excess = if (time >= 12_000) 12.0 else 0.0
                servo.observe(observation(1000 + time, 1000 + time, time),
                    observation(1000 + time, 900 + time + excess, time), packet * 200_000_000L)
                assertTrue(abs(servo.correctionPpm) <= MAX_DRIFT_CORRECTION_PPM)
                limited = limited || servo.snapshot(false).message.contains("capped")
            }
        }
        assertTrue(limited)
        assertTrue(failure.message!!.contains("for 5 seconds"))
        assertEquals(DriftPhase.Failed, servo.snapshot(false).phase)
    }
}
