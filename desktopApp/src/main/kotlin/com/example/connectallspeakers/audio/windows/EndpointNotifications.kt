package com.example.connectallspeakers.audio.windows

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.util.concurrent.atomic.AtomicInteger

/** IUnknown query ABI. */
internal fun interface QueryAudioInterface : StdCallCallback {
    /** @param self This pointer. @param iid Requested interface. @param result Returned pointer. @return HRESULT. */
    fun invoke(self: Pointer, iid: Pointer, result: PointerByReference): Int
}
/** IUnknown reference-count ABI. */
internal fun interface AudioReference : StdCallCallback {
    /** @param self This pointer. @return Updated reference count. */
    fun invoke(self: Pointer): Int
}
/** State-change ABI; native strings are borrowed and never retained. */
internal fun interface AudioStateChanged : StdCallCallback {
    /** @param self This pointer. @param id Borrowed ID. @param state Native state mask. @return HRESULT. */
    fun invoke(self: Pointer, id: Pointer?, state: Int): Int
}
/** Added/removed ABI. */
internal fun interface AudioDeviceChanged : StdCallCallback {
    /** @param self This pointer. @param id Borrowed ID. @return HRESULT. */
    fun invoke(self: Pointer, id: Pointer?): Int
}
/** Default-role change ABI. */
internal fun interface AudioDefaultChanged : StdCallCallback {
    /** @param self This pointer. @param flow Data flow. @param role Device role. @param id Nullable borrowed ID. @return HRESULT. */
    fun invoke(self: Pointer, flow: Int, role: Int, id: Pointer?): Int
}
/** Property-change ABI, including the SDK's by-value PROPERTYKEY. */
internal fun interface AudioPropertyChanged : StdCallCallback {
    /** @param self This pointer. @param id Borrowed ID. @param key By-value property key. @return HRESULT. */
    fun invoke(self: Pointer, id: Pointer?, key: PropertyKey.ByValue): Int
}

/** Strongly owns the complete eight-slot IMMNotificationClient vtable and callback trampolines.
 * Event callbacks only signal the bounded queue and never block, enumerate, or release COM objects.
 * @param onChanged Nonthrowing, nonblocking repository signal.
 */
internal class EndpointNotifications(private val onChanged: () -> Unit) {
    private val references = AtomicInteger(1)
    @Volatile private var enabled = true
    private val vtable = Memory(8L * Native.POINTER_SIZE)
    /** Native IMMNotificationClient address; borrowed by Windows until unregistered. */
    val pointer = Memory(Native.POINTER_SIZE.toLong())
    private val callbacks: List<Callback> = listOf(
        QueryAudioInterface { _, iid, result ->
            val requested = Guid.GUID(iid).toGuidString()
            if (requested.equals("{00000000-0000-0000-C000-000000000046}", true) ||
                requested.equals("{7991EEC9-7E89-4D85-8390-6C703CEC60C0}", true)) {
                result.value = pointer; references.incrementAndGet(); 0
            } else { result.value = null; 0x80004002.toInt() }
        },
        AudioReference { references.incrementAndGet() },
        AudioReference { references.decrementAndGet() },
        AudioStateChanged { _, _, _ -> signal() },
        AudioDeviceChanged { _, _ -> signal() },
        AudioDeviceChanged { _, _ -> signal() },
        AudioDefaultChanged { _, _, _, _ -> signal() },
        AudioPropertyChanged { _, _, _ -> signal() },
    )

    init {
        callbacks.forEachIndexed { index, callback -> vtable.setPointer(index.toLong() * Native.POINTER_SIZE,
            CallbackReference.getFunctionPointer(callback)) }
        pointer.setPointer(0, vtable)
    }

    /** Stops queuing events during teardown; native callback references remain alive. */
    fun disable() { enabled = false }

    /** Contains exceptions at the native boundary; normal event callbacks return S_OK.
     * @return HRESULT; no exception crosses the JNA callback boundary.
     */
    private fun signal(): Int = try { if (enabled) onChanged(); 0 } catch (_: Throwable) { 0x80004005.toInt() }
}
