package com.example.connectallspeakers

import com.example.connectallspeakers.audio.wasapi.PerformanceCounterApi
import com.example.connectallspeakers.audio.wasapi.WasapiClock
import com.example.connectallspeakers.audio.windows.AudioReference
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import kotlin.test.*

internal fun interface ClockFrequencyCallback : StdCallCallback {
    fun invoke(self: Pointer, frequency: LongByReference): Int
}
internal fun interface ClockPositionCallback : StdCallCallback {
    fun invoke(self: Pointer, position: LongByReference, qpc: LongByReference): Int
}

/** Calls real JNA vtable pointers backed by memory, never a native audio endpoint. */
class WasapiClockTest {
    private class Clock : AutoCloseable {
        var status = 0
        var frequencyStatus = 0
        var releases = 0
        val release = AudioReference { releases++; 0 }
        val frequency = ClockFrequencyCallback { _, result -> result.value = 19_200_000_000L; frequencyStatus }
        val position = ClockPositionCallback { _, result, qpc -> result.value = 9_600_000_000L; qpc.value = 30_000_000_000L; status }
        val vtable = Memory(5L * Native.POINTER_SIZE)
        val pointer = Memory(Native.POINTER_SIZE.toLong())
        init {
            vtable.clear()
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release))
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(frequency))
            vtable.setPointer(4L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(position))
            pointer.setPointer(0, vtable)
        }
        override fun close() { pointer.close(); vtable.close() }
    }
    private class Counter : PerformanceCounterApi {
        var succeeds = true
        var frequency = 1_000_000L
        override fun QueryPerformanceCounter(value: LongByReference): Boolean { value.value = 3_001_000_000L; return succeeds }
        override fun QueryPerformanceFrequency(value: LongByReference): Boolean { value.value = frequency; return succeeds }
    }

    @Test fun nativeSlotsUse64BitOutputsAndOnlyCurrentQpcIsConvertedToHundredNanoseconds() {
        Clock().use { native ->
            val clock = WasapiClock(native.pointer, Counter())
            val reading = assertNotNull(clock.read())
            assertEquals(19_200_000_000L, reading.frequencyTicksPerSecond)
            assertEquals(9_600_000_000L, reading.positionTicks)
            assertEquals(30_000_000_000L, reading.qpc100Nanos)
            assertEquals(30_010_000_000L, reading.observedQpc100Nanos)
            assertTrue(reading.accurate)
            clock.close(); clock.close()
            assertEquals(1, native.releases)
            assertFailsWith<IllegalStateException> { clock.read() }
        }
    }

    @Test fun nativeFailuresAreUnknownAndSFalseRemainsLowConfidenceWithoutRetrying() {
        Clock().use { native ->
            val counter = Counter()
            WasapiClock(native.pointer, counter).use { clock ->
                native.status = 1
                assertFalse(assertNotNull(clock.read()).accurate)
                native.status = 0x80004005.toInt()
                assertNull(clock.read())
                native.status = 0; native.frequencyStatus = 1
                assertNull(clock.read())
                native.frequencyStatus = 0; counter.succeeds = false
                assertNull(clock.read())
                counter.succeeds = true; counter.frequency = 0
                assertNull(clock.read())
            }
            assertEquals(1, native.releases)
        }
    }
}
