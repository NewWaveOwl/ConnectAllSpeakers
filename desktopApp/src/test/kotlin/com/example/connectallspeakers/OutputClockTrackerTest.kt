package com.example.connectallspeakers

import com.example.connectallspeakers.audio.wasapi.NativeClockReading
import com.example.connectallspeakers.audio.wasapi.OutputClockTracker
import kotlin.test.*

/** Native position ticks deliberately differ from source frames; no hardware is involved. */
class OutputClockTrackerTest {
    private fun reading(position: Long = 96_000, qpc: Long = 20_000_000) =
        NativeClockReading(position, 192_000, qpc, qpc + 10_000)

    @Test fun convertsUsingNativeFrequencyAndRetainsExplicitQpcUnits() {
        val tracker = OutputClockTracker()
        tracker.observe(reading(), 0, 600.0, false)
        assertEquals(500.0, tracker.value?.positionMillis)
        assertEquals(20_000_000L, tracker.value?.qpcPosition100Nanos)
        assertEquals(192_000L, tracker.value?.frequencyTicksPerSecond)
        assertNull(tracker.message)
    }

    @Test fun invalidLowConfidenceFutureAndStaleReadingsAreUnknownAndRecoverable() {
        val invalid = listOf(null, reading().copy(accurate = false), reading().copy(positionTicks = -1),
            reading().copy(frequencyTicksPerSecond = 0), reading().copy(frequencyTicksPerSecond = -1),
            reading().copy(qpc100Nanos = 0), reading().copy(observedQpc100Nanos = 19_999_999),
            reading().copy(observedQpc100Nanos = 30_000_001), reading().copy(positionTicks = Long.MAX_VALUE))
        invalid.forEach { sample ->
            val tracker = OutputClockTracker()
            tracker.observe(sample, 0, 600.0, false)
            assertNull(tracker.value, "$sample")
            assertNotNull(tracker.message)
            tracker.observe(reading(), 1, 600.0, false)
            assertNotNull(tracker.value)
        }
    }

    @Test fun backwardClockOrFrequencyChangeCannotReplaceValidHistory() {
        val tracker = OutputClockTracker()
        tracker.observe(reading(), 0, 600.0, false)
        for (sample in listOf(reading(95_999), reading(qpc = 19_999_999), reading().copy(frequencyTicksPerSecond = 384_000))) {
            tracker.observe(sample, 100, 600.0, false)
            assertNull(tracker.value)
            assertTrue(tracker.message!!.contains("discontinuity"))
        }
        tracker.observe(reading(100_000, 21_000_000), 200, 600.0, false)
        assertNotNull(tracker.value)
    }

    @Test fun nonAdvancingClockExpiresAndResumeRetainsMonotonicHistoryWithoutCountingPauseAsStall() {
        val tracker = OutputClockTracker()
        tracker.observe(reading(), 0, 600.0, false)
        tracker.observe(reading(qpc = 30_000_000), 1_000_000_001, 600.0, false)
        assertNull(tracker.value)
        tracker.observe(reading(), 9_000_000_000, 600.0, true)
        assertNull(tracker.value)
        tracker.observe(reading(qpc = 31_000_000), 9_000_000_001, 600.0, false)
        assertNotNull(tracker.value)
        tracker.observe(reading(1, 32_000_000), 9_100_000_000, 600.0, false)
        assertNull(tracker.value) // Pause did not permit the native position to rewind.
    }
}
