package com.example.connectallspeakers

import com.example.connectallspeakers.audio.windows.*
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.PointerByReference
import kotlin.test.*

/** Exercises the real JNA callback ABI through native function pointers, without live hardware. */
class EndpointNotificationsTest {
    @Test
    fun allNotificationSlotsSignalAndPropertyKeyUsesTheByValueSdkLayout() {
        var signals = 0
        val listener = EndpointNotifications { signals++ }
        assertEquals(0, comCall(listener.pointer, 3, null, 1))
        assertEquals(0, comCall(listener.pointer, 4, null))
        assertEquals(0, comCall(listener.pointer, 5, null))
        assertEquals(0, comCall(listener.pointer, 6, 0, 1, null))
        val key = PropertyKey.ByValue().apply {
            fmtid = Guid.GUID("{1DA5D803-D492-4EDD-8C23-E0C0FFEE7F0E}")
            pid = 0
            write()
        }
        assertEquals(20, key.size())
        assertEquals(0, comCall(listener.pointer, 7, null, key))
        assertEquals(5, signals)
        listener.disable()
        assertEquals(0, comCall(listener.pointer, 4, null))
        assertEquals(5, signals)
    }

    @Test
    fun queryInterfaceSupportsOnlyIUnknownAndTheNotificationInterface() {
        val listener = EndpointNotifications {}
        val result = PointerByReference()
        for (iid in listOf("{00000000-0000-0000-C000-000000000046}", "{7991EEC9-7E89-4D85-8390-6C703CEC60C0}")) {
            val guid = Guid.GUID(iid).apply { write() }
            assertEquals(0, comCall(listener.pointer, 0, guid.pointer, result))
            assertEquals(Pointer.nativeValue(listener.pointer), Pointer.nativeValue(result.value))
            assertEquals(1, comCall(listener.pointer, 2))
        }
        val unsupported = Guid.GUID("{00000000-0000-0000-0000-000000000000}").apply { write() }
        assertEquals(0x80004002.toInt(), comCall(listener.pointer, 0, unsupported.pointer, result))
        assertNull(result.value)
    }

    @Test
    fun callbackExceptionsNeverEscapeIntoNativeCode() {
        val listener = EndpointNotifications { error("Controlled queue failure") }
        assertEquals(0x80004005.toInt(), comCall(listener.pointer, 4, null))
    }
}
