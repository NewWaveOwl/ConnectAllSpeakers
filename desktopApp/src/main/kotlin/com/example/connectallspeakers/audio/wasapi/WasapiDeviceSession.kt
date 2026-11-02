package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.audio.windows.checkAudioResult
import com.example.connectallspeakers.audio.windows.comCall
import com.example.connectallspeakers.audio.windows.releaseCom
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Shared-mode WASAPI binding. Construct, use and close on the same dedicated MTA thread.
 * Construction is allowed only after an explicit Play/Test command, never for UI previews/tests.
 */
internal class WasapiDeviceSession : PlaybackDeviceSession {
    private var enumerator: Pointer? = null
    private var initialized = false

    init {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            (System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0) >= 10) {
            "This playback backend requires Windows 10 or Windows 11."
        }
        checkAudioResult("Initialize playback COM", Ole32.INSTANCE.CoInitializeEx(null, 0).toInt())
        initialized = true
        try {
            val result = PointerByReference()
            checkAudioResult("Create playback enumerator", Ole32.INSTANCE.CoCreateInstance(
                Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 1,
                Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), result).toInt())
            enumerator = requireNotNull(result.value)
        } catch (failure: Throwable) { close(); throw failure }
    }

    /** Opens only the requested active render endpoint; never substitutes the default output.
     * @param endpointId Opaque Windows render ID.
     * @param sampleRate Source-rate float frames/second; Windows converts to the device mix rate.
     * @return Owned stopped stream, requiring close before the session closes.
     */
    override fun openEndpoint(endpointId: String, sampleRate: Int): PlaybackEndpoint {
        val deviceResult = PointerByReference()
        checkAudioResult("Find selected playback output", comCall(checkNotNull(enumerator), 5, WString(endpointId), deviceResult))
        val device = requireNotNull(deviceResult.value)
        var client: Pointer? = null
        var render: Pointer? = null
        try {
            val state = IntByReference()
            checkAudioResult("Check playback availability", comCall(device, 6, state))
            check(state.value and 1 != 0) { "The selected output is unavailable." }
            val endpointResult = PointerByReference()
            val endpointIid = Guid.GUID("{1BE09788-6894-4089-8586-9A2A6C265AC5}").also { it.write() }
            checkAudioResult("Check endpoint direction", comCall(device, 0, endpointIid.pointer, endpointResult))
            val endpoint = requireNotNull(endpointResult.value)
            try {
                val flow = IntByReference()
                checkAudioResult("Read endpoint direction", comCall(endpoint, 3, flow))
                check(flow.value == 0) { "Only playback outputs are supported." }
            } finally { releaseCom(endpoint) }
            val clientIid = Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}").also { it.write() }
            val clientResult = PointerByReference()
            checkAudioResult("Activate selected output", comCall(device, 3, clientIid.pointer, 23, null, clientResult))
            client = requireNotNull(clientResult.value)
            val mixResult = PointerByReference()
            checkAudioResult("Read output mix format", comCall(client, 8, mixResult))
            val mix = requireNotNull(mixResult.value)
            val channels = try { mix.getShort(2).toInt() and 0xFFFF } finally { Ole32.INSTANCE.CoTaskMemFree(mix) }
            check(channels in 1..2) { "This output uses more than two channels. Set it to stereo in Windows Sound settings." }
            Memory(18).use { format ->
                format.clear()
                format.setShort(0, 3) // WAVE_FORMAT_IEEE_FLOAT
                format.setShort(2, channels.toShort())
                format.setInt(4, sampleRate)
                format.setInt(8, sampleRate * channels * 4)
                format.setShort(12, (channels * 4).toShort())
                format.setShort(14, 32)
                // Shared, automatic PCM conversion, high-quality SRC, no persisted session gain.
                checkAudioResult("Initialize output stream", comCall(client, 3, 0, 0x88080000.toInt(),
                    1_000_000L, 0L, format, null)) // 100 ms in 100-ns units
            }
            val capacity = IntByReference()
            checkAudioResult("Read output buffer capacity", comCall(client, 4, capacity))
            check(capacity.value in 1..sampleRate * 2) { "The output requested an unsupported buffer size." }
            val renderIid = Guid.GUID("{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}").also { it.write() }
            val renderResult = PointerByReference()
            checkAudioResult("Create render client", comCall(client, 14, renderIid.pointer, renderResult))
            render = requireNotNull(renderResult.value)
            val result = WasapiEndpoint(client, render, channels, capacity.value)
            client = null
            render = null
            return result
        } finally {
            try { releaseCom(render) } finally { try { releaseCom(client) } finally { releaseCom(device) } }
        }
    }

    /** Releases the enumerator and balances COM on its initializing thread; idempotent. */
    override fun close() {
        try { releaseCom(enumerator) } finally {
            enumerator = null
            if (initialized) { initialized = false; Ole32.INSTANCE.CoUninitialize() }
        }
    }
}

/** Owned IAudioClient/IAudioRenderClient references; no native buffer survives submit.
 * @param client Owned audio client.
 * @param render Owned render service.
 * @property channels Physical output channels.
 * @property capacityFrames Buffer capacity in requested source-rate frames.
 */
private class WasapiEndpoint(private val client: Pointer, private val render: Pointer,
    override val channels: Int, override val capacityFrames: Int) : PlaybackEndpoint {
    private val padding = IntByReference()
    private val buffer = PointerByReference()
    private var closed = false

    /** @return Current engine padding in source-rate frames. */
    override fun queuedFrames(): Int {
        checkAudioResult("Read playback padding", comCall(client, 6, padding))
        return padding.value
    }

    /** Copies a complete block and balances GetBuffer/ReleaseBuffer even if copying fails.
     * @param samples Borrowed preallocated float32 bytes.
     * @param frames Number of complete frames, bounded by native capacity.
     */
    override fun submit(samples: ByteArray, frames: Int) {
        require(frames in 1..capacityFrames && samples.size >= frames * channels * 4)
        checkAudioResult("Acquire render buffer", comCall(render, 3, frames, buffer))
        var copied = false
        try {
            requireNotNull(buffer.value).write(0, samples, 0, frames * channels * 4)
            copied = true
        } finally {
            checkAudioResult("Release render buffer", comCall(render, 4, frames, if (copied) 0 else 2))
        }
    }

    /** Starts/resumes the explicitly requested endpoint. */
    override fun start() { checkAudioResult("Start output", comCall(client, 10)) }
    /** Stops engine consumption, retaining the queue. */
    override fun stop() { checkAudioResult("Pause output", comCall(client, 11)) }
    /** Clears the stopped queue. */
    override fun reset() { checkAudioResult("Reset output", comCall(client, 12)) }
    /** Releases both references once. Session orchestration stops/resets before this call. */
    override fun close() {
        if (closed) return
        closed = true
        try { releaseCom(render) } finally { releaseCom(client) }
    }
}
