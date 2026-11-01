package com.example.connectallspeakers.data.audio

import com.example.connectallspeakers.model.audio.AudioOutputDevice
import kotlinx.coroutines.flow.StateFlow

/** Discovery lifecycle, independent of whether a list is empty. */
enum class AudioOutputStatus { Loading, Ready, Error, Unsupported }

/** Repository state retains the last successful list during loading/error without claiming freshness.
 * @property status Whether devices constitute a current authoritative enumeration.
 * @property devices Active playback endpoints from the last successful enumeration.
 * @property message User-readable failure/unsupported explanation, or null.
 * @property revision Successful enumeration count, useful for explicit refresh completion.
 */
data class AudioOutputSnapshot(
    val status: AudioOutputStatus = AudioOutputStatus.Loading,
    val devices: List<AudioOutputDevice> = emptyList(),
    val message: String? = null,
    val revision: Long = 0,
)

/** Platform-neutral playback-output discovery; the platform application owns native lifetime. */
interface AudioOutputRepository {
    /** Current discovery result, including loading/empty/error states. */
    val outputs: StateFlow<AudioOutputSnapshot>

    /** Queues a nonblocking refresh; repeated requests may be coalesced. */
    fun refresh()
}
