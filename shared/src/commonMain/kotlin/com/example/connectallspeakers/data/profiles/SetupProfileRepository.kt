package com.example.connectallspeakers.data.profiles

import com.example.connectallspeakers.model.profiles.SetupProfile

/** Portable explicit-save boundary. The desktop owner supplies asynchronous per-user storage.
 * Implementations serialize operations and never replace a valid profile with a partial write.
 * Errors propagate for truthful UI feedback; cancellation is not converted into success.
 */
interface SetupProfileRepository {
    /** Loads/validates settings without opening any audio streams.
     * @return Saved configuration, or null only when no file exists.
     * @throws Exception For unreadable, malformed, oversized or unsupported data. Keep that file intact.
     */
    suspend fun load(): SetupProfile?

    /** Completes only after validated settings have been flushed and atomically installed.
     * @param profile Snapshot independent of ongoing edits; excludes all live/native state.
     * @throws Exception On failure; the previous valid file must remain readable.
     */
    suspend fun save(profile: SetupProfile)
}
