package com.example.connectallspeakers.audio.windows

import com.example.connectallspeakers.model.audio.AudioOutputRole
import com.sun.jna.Pointer

/** Matches the endpoint property installed by our native INF, not any editable device label. */
internal object AllConnectEndpointIdentity {
    const val PROPERTY_GUID = "{A9CB07B6-15EA-4D40-96A6-0D7418CE92AD}"
    const val PROPERTY_ID = 1

    /** Reads ownership from a borrowed IMMDevice, closing its property store on every path.
     * @param device Borrowed render endpoint on the caller's COM thread.
     * @return Verified role; native read failures mean Unknown and fail closed for routing.
     */
    fun role(device: Pointer): AudioOutputRole {
        val result = com.sun.jna.ptr.PointerByReference()
        if (comCall(device, 4, 0, result) < 0 || result.value == null) return AudioOutputRole.Unknown
        val store = result.value
        return try {
            readProperty(store, PropertyKey(PROPERTY_GUID, PROPERTY_ID)) {
                when (it.getShort(0).toInt()) {
                    0 -> AudioOutputRole.PlaybackTarget // VT_EMPTY: property absent on ordinary endpoints.
                    19 -> if (it.getInt(8) == 1) AudioOutputRole.AllConnectInput else AudioOutputRole.Unknown
                    else -> AudioOutputRole.Unknown
                }
            }
        } catch (failure: CoreAudioException) {
            // PKEY absence can also be returned as HRESULT_FROM_WIN32(ERROR_NOT_FOUND).
            if (failure.resultCode == 0x80070490.toInt()) AudioOutputRole.PlaybackTarget else AudioOutputRole.Unknown
        } finally { releaseCom(store) }
    }
}
