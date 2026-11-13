package com.example.connectallspeakers.audio.input.windows

import com.example.connectallspeakers.audio.input.LiveCaptureSource
import com.example.connectallspeakers.audio.input.LivePcmConsumer
import com.example.connectallspeakers.audio.wasapi.PerformanceCounterApi
import com.example.connectallspeakers.audio.windows.*
import com.example.connectallspeakers.model.audio.*
import com.sun.jna.*
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.*

/** Explicit endpoint/process loopback. Construct/use/close only on the audio owner's MTA worker.
 * No default endpoint, microphone, recording file or external mixer is used.
 * @param endpointId Explicit driver-identified AllConnect input.
 * @param processApp Optional selected process identity; bypasses endpoint discovery using process-loopback activation.
 */
internal class WasapiLoopbackCapture(endpointId: String, processApp: CaptureApp? = null) : LiveCaptureSource {
    private var initialized = false
    private var enumerator: Pointer? = null
    private var device: Pointer? = null
    private var client: Pointer? = null
    private var capture: Pointer? = null
    private var started = false
    private var closed = false
    private val pointer = PointerByReference()
    private val frames = IntByReference()
    private val flags = IntByReference()
    private val deviceFrame = LongByReference()
    private val packetQpc = LongByReference()
    private val nextPacket = IntByReference()
    private val counter = LongByReference()
    private val counterFrequency = LongByReference()
    private val performance = Native.load("kernel32", PerformanceCounterApi::class.java)
    private var samples = FloatArray(0)
    private var pcm16 = ShortArray(0)
    private var capacity = 0
    private var floatFormat = false

    init {
        try {
            checkAudioResult("Initialize virtual input COM", Ole32.INSTANCE.CoInitializeEx(null, 0).toInt())
            initialized = true
            val result = PointerByReference()
            if (processApp != null) {
                checkAudioResult("Activate selected app audio (Windows build 20348 or newer required)", ProcessAudioApps.activate(processApp, result))
                client = checkNotNull(result.value)
                // Process loopback does not implement GetMixFormat. Request a fixed PCM format
                // with Windows conversion, as supported by the process-loopback API.
                val format = Memory(18).apply {
                    clear(); setShort(0, 1); setShort(2, 2); setInt(4, LIVE_SAMPLE_RATE)
                    setInt(8, LIVE_SAMPLE_RATE * 4); setShort(12, 4); setShort(14, 16)
                }
                try {
                    checkAudioResult("Initialize selected app loopback", comCall(client!!, 3, 0, 0x80020000.toInt(),
                        1_000_000L, 0L, format, null))
                } finally { format.close() }
            } else {
                checkAudioResult("Create virtual input enumerator", Ole32.INSTANCE.CoCreateInstance(
                    Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 1,
                    Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), result).toInt())
                enumerator = checkNotNull(result.value)
                checkAudioResult("Find virtual input", comCall(enumerator!!, 5, WString(endpointId), result))
                device = checkNotNull(result.value)
                check(AllConnectEndpointIdentity.role(device!!) == AudioOutputRole.AllConnectInput) { "The selected input is not an identified AllConnect virtual endpoint." }
                val state = IntByReference()
                checkAudioResult("Read virtual input state", comCall(device!!, 6, state))
                check(state.value and 1 != 0) { "AllConnect virtual input is unavailable." }
                val directionIid = Guid.GUID("{1BE09788-6894-4089-8586-9A2A6C265AC5}").also { it.write() }
                checkAudioResult("Read virtual input direction", comCall(device!!, 0, directionIid.pointer, result))
                val direction = checkNotNull(result.value)
                try { checkAudioResult("Check virtual input direction", comCall(direction, 3, state)); check(state.value == 0) { "Only our render endpoint can supply virtual input." } }
                finally { releaseCom(direction) }
                val iid = Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}").also { it.write() }
                checkAudioResult("Activate virtual input", comCall(device!!, 3, iid.pointer, 23, null, result))
                client = checkNotNull(result.value)
                checkAudioResult("Read virtual input format", comCall(client!!, 8, result))
                val format = checkNotNull(result.value)
                try {
                    val bytes = format.getByteArray(0, 18)
                    val extensionSize = format.getShort(16).toInt() and 65535
                    require(extensionSize <= 64) { "Unsupported virtual input format extension." }
                    val complete = if (extensionSize == 0) bytes else format.getByteArray(0, 18 + extensionSize)
                    floatFormat = LiveCaptureFormat.parse(complete)
                    checkAudioResult("Initialize owned endpoint loopback", comCall(client!!, 3, 0, 0x00020000,
                        1_000_000L, 0L, format, null)) // Shared, LOOPBACK, 100 ms; exact negotiated mix format.
                } finally { Ole32.INSTANCE.CoTaskMemFree(format) }
            }
            checkAudioResult("Read capture capacity", comCall(client!!, 4, frames))
            capacity = frames.value
            require(capacity in 1..LIVE_SAMPLE_RATE) { "Virtual input buffer exceeds the one-second capture limit." }
            samples = FloatArray(capacity * LIVE_CHANNELS)
            pcm16 = ShortArray(capacity * LIVE_CHANNELS)
            val captureIid = Guid.GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}").also { it.write() }
            checkAudioResult("Create loopback capture service", comCall(client!!, 14, captureIid.pointer, result))
            capture = checkNotNull(result.value)
            check(performance.QueryPerformanceFrequency(counterFrequency) && counterFrequency.value > 0) { "The input performance clock is unavailable." }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Begins the fresh input timeline after target preparation; construction alone does not capture. */
    override fun start() {
        check(!closed && !started)
        checkAudioResult("Start virtual input capture", comCall(client!!, 10))
        started = true
    }

    /** @param consumer Borrowed same-worker destination. @return Available frames; never waits for audio. */
    override fun poll(consumer: LivePcmConsumer): Int {
        check(!closed && started)
        var total = 0
        repeat(32) {
            checkAudioResult("Read next loopback packet size", comCall(capture!!, 5, nextPacket))
            if (nextPacket.value == 0) return total
            checkAudioResult("Acquire loopback packet", comCall(capture!!, 3, pointer, frames, flags, deviceFrame, packetQpc))
            val count = frames.value
            try {
                if (count == 0) return total
                require(count in 1..capacity) { "Invalid loopback packet length." }
                if (flags.value and 2 != 0) samples.fill(0f, 0, count * 2) else {
                    val data = checkNotNull(pointer.value) { "Loopback data is unavailable." }
                    if (floatFormat) data.read(0, samples, 0, count * 2) else {
                        data.read(0, pcm16, 0, count * 2)
                        for (index in 0 until count * 2) samples[index] = pcm16[index] / 32768f
                    }
                }
                check(performance.QueryPerformanceCounter(counter)) { "The input performance clock failed." }
                val now = (counter.value.toDouble() / counterFrequency.value * 10_000_000).toLong()
                consumer.accept(samples, count, deviceFrame.value, packetQpc.value, now,
                    flags.value and 1 != 0, flags.value and 4 != 0)
                total += count
            } finally { checkAudioResult("Release loopback packet", comCall(capture!!, 4, count)) }
        }
        error("Virtual input packets exceeded the bounded polling budget. Stop and restart.")
    }

    /** Balances capture/client/device/enumerator/COM lifetime on the creating worker, including partial failure. */
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) { try { action() } catch (problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } } }
        if (started && client != null) cleanup { checkAudioResult("Stop virtual input", comCall(client!!, 11)) }
        cleanup { releaseCom(capture) }; capture = null
        cleanup { releaseCom(client) }; client = null
        cleanup { releaseCom(device) }; device = null
        cleanup { releaseCom(enumerator) }; enumerator = null
        samples.fill(0f)
        pcm16.fill(0)
        if (initialized) { initialized = false; Ole32.INSTANCE.CoUninitialize() }
        failure?.let { throw it }
    }
}

/** Strict decoder for the only prototype mix formats; false means PCM16, true means float32. */
internal object LiveCaptureFormat {
    /** @param bytes Copied WAVEFORMATEX/EXTENSIBLE. @return True for IEEE float32.
     * @throws IllegalArgumentException For any unsupported rate/layout/encoding/size.
     */
    fun parse(bytes: ByteArray): Boolean {
        require(bytes.size >= 18) { "Truncated virtual input format." }
        fun short(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun integer(offset: Int) = short(offset) or (short(offset + 2) shl 16)
        val tag = short(0); val channels = short(2); val bits = short(14); val extension = short(16)
        require(extension in 0..64 && bytes.size >= 18 + extension) { "Truncated or oversized virtual input format extension." }
        require(channels == 2 && integer(4) == LIVE_SAMPLE_RATE) { "Set AllConnect Speakers to 48000 Hz stereo in Windows Sound settings." }
        val encoding = if (tag == 0xfffe) {
            require(extension >= 22 && bytes.size >= 40 && short(18) == bits && integer(20) in listOf(0, 3)) { "Unsupported virtual input channel layout or valid bits." }
            val expectedTail = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
            require(bytes.copyOfRange(28, 40).contentEquals(expectedTail) && short(26) == 0) { "Unsupported virtual input subformat." }
            short(24)
        } else { require(extension == 0) { "Unsupported virtual input format extension." }; tag }
        require((encoding == 1 && bits == 16) || (encoding == 3 && bits == 32)) { "Virtual input supports PCM16 or IEEE float32 only." }
        require(short(12) == channels * bits / 8 && integer(8) == LIVE_SAMPLE_RATE * channels * bits / 8) { "Invalid virtual input block alignment." }
        return encoding == 3
    }
}
