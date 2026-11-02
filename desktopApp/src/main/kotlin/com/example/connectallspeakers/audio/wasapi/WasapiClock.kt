package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.audio.windows.comCall
import com.example.connectallspeakers.audio.windows.releaseCom
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary

/** Kernel performance-counter API; LONG LONG storage and BOOL return match the Win32 ABI. */
internal interface PerformanceCounterApi : StdCallLibrary {
    /** @param value Output signed 64-bit counter. @return Whether the read succeeded. */
    fun QueryPerformanceCounter(value: LongByReference): Boolean
    /** @param value Output ticks/second. @return Whether the read succeeded. */
    fun QueryPerformanceFrequency(value: LongByReference): Boolean
}

/** Owns an optional IAudioClock reference on the endpoint's COM worker.
 * @param pointer Owned clock reference; must be released before its parent client.
 * @param performance Counter adapter; tests inject an in-memory implementation.
 */
internal class WasapiClock(private val pointer: Pointer,
    private val performance: PerformanceCounterApi = Native.load("kernel32", PerformanceCounterApi::class.java),
) : AutoCloseable {
    private val frequency = LongByReference()
    private val position = LongByReference()
    private val correlatedQpc = LongByReference()
    private val currentQpc = LongByReference()
    private val counterFrequency = LongByReference()
    private var closed = false

    /** @return Copied correlated values, or null on unsupported/failed diagnostics. No retry loop. */
    fun read(): NativeClockReading? {
        check(!closed) { "Audio clock is closed." }
        if (comCall(pointer, 3, frequency) != 0) return null // IAudioClock::GetFrequency
        val status = comCall(pointer, 4, position, correlatedQpc)
        if (status < 0 || !performance.QueryPerformanceFrequency(counterFrequency) ||
            !performance.QueryPerformanceCounter(currentQpc)) return null
        val scale = counterFrequency.value
        if (scale <= 0 || currentQpc.value < 0) return null
        val converted = currentQpc.value.toDouble() / scale * 10_000_000.0
        if (!converted.isFinite() || converted >= Long.MAX_VALUE.toDouble()) return null
        return NativeClockReading(position.value, frequency.value, correlatedQpc.value, converted.toLong(), status == 0)
    }

    /** Releases the clock reference once, on its acquiring worker. */
    override fun close() { if (!closed) { closed = true; releaseCom(pointer) } }
}
