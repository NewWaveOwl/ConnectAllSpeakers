package com.example.connectallspeakers.audio.windows

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.win32.StdCallLibrary

/** Ole32's PROPVARIANT cleanup API, absent from JNA's Ole32 interface. */
internal interface PropertyVariantApi : StdCallLibrary {
    /** @param value Initialized PROPVARIANT storage. @return HRESULT. */
    fun PropVariantClear(value: Pointer): Int
}

private val propertyVariantApi by lazy { Native.load("ole32", PropertyVariantApi::class.java) }

/** PROPERTYKEY layout from the Windows SDK: a GUID followed by a DWORD property ID.
 * @param formatId Property-set GUID.
 * @param propertyId Property index in that set.
 */
@Structure.FieldOrder("fmtid", "pid")
internal open class PropertyKey(formatId: String? = null, propertyId: Int = 0) : Structure() {
    @JvmField var fmtid = if (formatId == null) Guid.GUID() else Guid.GUID(formatId)
    @JvmField var pid = propertyId

    /** By-value ABI used by IMMNotificationClient::OnPropertyValueChanged. */
    class ByValue : PropertyKey(), Structure.ByValue
}

/** HRESULT failure without exposing endpoint identifiers in error messages.
 * @param operation Native operation that failed.
 * @param result Signed 32-bit HRESULT.
 */
internal class CoreAudioException(operation: String, result: Int) :
    IllegalStateException("$operation failed (0x${result.toUInt().toString(16).padStart(8, '0')}).")

/** Checks HRESULT success, including nonzero success codes.
 * @param operation Native operation label.
 * @param result Native HRESULT.
 */
internal fun checkAudioResult(operation: String, result: Int) {
    if (result < 0) throw CoreAudioException(operation, result)
}

/** Calls a Windows COM vtable slot using the Windows stdcall ABI (unified on x64).
 * @param target COM interface pointer, owned by the caller.
 * @param slot Zero-based slot including IUnknown's three methods.
 * @param arguments Arguments after the implicit this pointer.
 * @return Native 32-bit result.
 */
internal fun comCall(target: Pointer, slot: Int, vararg arguments: Any?): Int {
    val function = target.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
    return Function.getFunction(function, Function.ALT_CONVENTION).invokeInt(arrayOf(target, *arguments))
}

/** Releases one owned COM interface reference.
 * @param target Owned reference, or null when initialization did not reach acquisition.
 */
internal fun releaseCom(target: Pointer?) {
    if (target != null) comCall(target, 2)
}

/** Reads one PROPVARIANT, always clearing any native value before freeing its storage.
 * @param store Borrowed IPropertyStore pointer.
 * @param key Property key passed by reference.
 * @param read Converts the borrowed variant while its allocation remains valid.
 * @return A copied JVM value; no native pointers may escape read.
 */
internal fun <T> readProperty(store: Pointer, key: PropertyKey, read: (Pointer) -> T): T {
    key.write()
    // PROPVARIANT is 24 bytes on x64 and 16 on x86. Extra zeroed capacity is safe on x86.
    Memory(24).use { variant ->
        variant.clear()
        try {
            checkAudioResult("Read endpoint property", comCall(store, 5, key.pointer, variant))
            return read(variant)
        } finally {
            checkAudioResult("Clear endpoint property", propertyVariantApi.PropVariantClear(variant))
        }
    }
}
