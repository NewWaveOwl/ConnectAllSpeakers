package com.example.connectallspeakers.audio.windows

import com.example.connectallspeakers.model.audio.AudioOutputDevice
import com.example.connectallspeakers.model.audio.AudioOutputType
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference
import java.util.concurrent.ConcurrentLinkedQueue

/** Small Core Audio enumeration binding verified against mmdeviceapi.h and propsys.h.
 * Construct, open, enumerate and close only on the repository's dedicated COM MTA thread.
 */
internal class CoreAudioDeviceBackend : WindowsAudioBackend {
    private var enumerator: Pointer? = null
    private var notifications: EndpointNotifications? = null
    private var initialized = false
    private var registered = false

    /** Initializes MTA COM, creates the enumerator and subscribes before the first snapshot.
     * @param onChanged Nonblocking refresh signal.
     */
    override fun open(onChanged: () -> Unit) {
        check(!initialized) { "Core Audio backend is already open." }
        checkAudioResult("Initialize Core Audio", Ole32.INSTANCE.CoInitializeEx(null, 0).toInt())
        initialized = true
        try {
            val result = PointerByReference()
            checkAudioResult("Create endpoint enumerator", Ole32.INSTANCE.CoCreateInstance(
                Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 1,
                Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), result,
            ).toInt())
            enumerator = requireNotNull(result.value)
            notifications = EndpointNotifications(onChanged)
            checkAudioResult("Subscribe to endpoint changes", comCall(enumerator!!, 6, notifications!!.pointer))
            registered = true
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Returns active render endpoints only, with a deliberate multimedia default role.
     * @return Copied OS metadata; no COM pointers or property strings escape.
     */
    override fun enumerate(): List<AudioOutputDevice> {
        val owner = checkNotNull(enumerator)
        val defaultId = defaultEndpointId(owner)
        val collectionResult = PointerByReference()
        // eRender = 0, DEVICE_STATE_ACTIVE = 1. Capture endpoints are never requested.
        checkAudioResult("Enumerate playback outputs", comCall(owner, 3, 0, 1, collectionResult))
        val collection = requireNotNull(collectionResult.value)
        try {
            val count = IntByReference()
            checkAudioResult("Count playback outputs", comCall(collection, 3, count))
            return buildList {
                for (index in 0 until count.value) {
                    val deviceResult = PointerByReference()
                    checkAudioResult("Read playback output", comCall(collection, 4, index, deviceResult))
                    val device = requireNotNull(deviceResult.value)
                    try {
                        val state = IntByReference()
                        checkAudioResult("Read playback output state", comCall(device, 6, state))
                        if (state.value and 1 != 0) add(readDevice(device, defaultId))
                    } finally { releaseCom(device) }
                }
            }
        } finally { releaseCom(collection) }
    }

    /** Unregisters before releasing the enumerator and balancing COM initialization. */
    override fun close() {
        val owner = enumerator
        val listener = notifications
        listener?.disable()
        try {
            if (registered && owner != null && listener != null) {
                val result = comCall(owner, 7, listener.pointer)
                if (result < 0) retainedAfterUnregisterFailure.add(listener)
                checkAudioResult("Unsubscribe from endpoint changes", result)
            }
        } finally {
            registered = false
            try { releaseCom(owner) } finally {
                enumerator = null
                notifications = null
                if (initialized) { initialized = false; Ole32.INSTANCE.CoUninitialize() }
                Reference.reachabilityFence(listener)
            }
        }
    }

    /** Copies the multimedia default's opaque ID, allowing Windows to have no default.
     * @param owner Borrowed enumerator.
     * @return Default ID, or null only for E_NOTFOUND.
     */
    private fun defaultEndpointId(owner: Pointer): String? {
        val result = PointerByReference()
        val status = comCall(owner, 4, 0, 1, result)
        if (status == 0x80070490.toInt()) return null
        checkAudioResult("Read default playback output", status)
        val device = requireNotNull(result.value)
        try { return endpointId(device) } finally { releaseCom(device) }
    }

    /** Copies an ID and frees the COM task allocation returned by IMMDevice::GetId.
     * @param device Borrowed endpoint interface.
     * @return Unparsed endpoint ID.
     */
    private fun endpointId(device: Pointer): String {
        val result = PointerByReference()
        checkAudioResult("Read endpoint identity", comCall(device, 5, result))
        val text = requireNotNull(result.value)
        try { return text.getWideString(0) } finally { Ole32.INSTANCE.CoTaskMemFree(text) }
    }

    /** Reads friendly-name/form-factor metadata without inferring Bluetooth or USB from names.
     * @param device Borrowed active render endpoint.
     * @param defaultId Multimedia default ID, if any.
     * @return Immutable metadata.
     */
    private fun readDevice(device: Pointer, defaultId: String?): AudioOutputDevice {
        val id = endpointId(device)
        val result = PointerByReference()
        checkAudioResult("Open endpoint properties", comCall(device, 4, 0, result)) // STGM_READ
        val store = requireNotNull(result.value)
        try {
            val name = readProperty(store, PropertyKey("{A45C254E-DF1C-4EFD-8020-67D146A850E0}", 14)) {
                if (it.getShort(0).toInt() == 31) it.getPointer(8)?.getWideString(0) else null // VT_LPWSTR
            }?.takeIf { it.isNotBlank() } ?: "Unnamed Windows output"
            val formFactor = readProperty(store, PropertyKey("{1DA5D803-D492-4EDD-8C23-E0C0FFEE7F0E}", 0)) {
                if (it.getShort(0).toInt() == 19) it.getInt(8) else null // VT_UI4
            }
            return AudioOutputDevice(id, name, isDefault = id == defaultId, type = when (formFactor) {
                0 -> AudioOutputType.RemoteNetwork; 1 -> AudioOutputType.Speakers; 2 -> AudioOutputType.LineLevel
                3 -> AudioOutputType.Headphones; 5 -> AudioOutputType.Headset; 6 -> AudioOutputType.Handset
                7 -> AudioOutputType.DigitalPassthrough; 8 -> AudioOutputType.Spdif; 9 -> AudioOutputType.DigitalDisplay
                else -> AudioOutputType.Unknown
            })
        } finally { releaseCom(store) }
    }

    companion object {
        // If Windows rejects unregistration, keep trampolines alive until process exit rather than risk native UAF.
        private val retainedAfterUnregisterFailure = ConcurrentLinkedQueue<EndpointNotifications>()
    }
}
