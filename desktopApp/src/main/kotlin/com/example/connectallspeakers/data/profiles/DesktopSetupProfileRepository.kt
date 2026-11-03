package com.example.connectallspeakers.data.profiles

import com.example.connectallspeakers.model.profiles.MAX_PROFILE_BYTES
import com.example.connectallspeakers.model.profiles.SetupProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Per-user, bounded file storage. Every filesystem call runs on IO, serialized with close.
 * A forced same-directory temporary file is atomically replaced; there is no non-atomic fallback.
 * The lock coordinates app instances; byte comparison rejects changes since the last successful load.
 * The desktop application owns this repository and awaits [close] before exiting.
 * @param directory Lazily resolved directory; errors are reported through load, never desktop construction.
 * @param beforeReplace Test-only failure seam after forcing the temp file and before atomic replacement.
 */
class DesktopSetupProfileRepository internal constructor(
    private val directory: () -> Path,
    private val beforeReplace: (Path) -> Unit,
) : SetupProfileRepository {
    /** Uses Windows LocalAppData, or a caller-supplied temporary directory in offline tests.
     * @param directory Per-user directory resolver; no I/O occurs in this constructor.
     */
    constructor(directory: () -> Path = ::defaultProfileDirectory) : this(directory, {})

    private val mutex = Mutex()
    private var closed = false
    private var loaded = false
    private var knownBytes: ByteArray? = null

    /** @return Validated saved settings or null only for an absent file. No audio service is accessed. */
    override suspend fun load(): SetupProfile? = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Setup storage is closed." }
            loaded = false
            storageOperation { root ->
                val bytes = readBounded(root.resolve(PROFILE_FILE))
                val profile = bytes?.let(SetupProfileCodec::decode)
                knownBytes = bytes
                loaded = true
                profile
            }
        }
    }

    /** @param profile Independent validated snapshot. A failure before replace leaves the previous bytes untouched. */
    override suspend fun save(profile: SetupProfile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Setup storage is closed." }
            check(loaded) { "Load saved setups successfully before saving. Move a damaged or unsupported file aside, then retry loading." }
            val bytes = SetupProfileCodec.encode(profile)
            storageOperation { root ->
                val target = root.resolve(PROFILE_FILE)
                val current = readBounded(target)
                check(equalBytes(current, knownBytes)) { "Saved setups changed outside this window. Reload saved setups before saving again." }
                val temporary = Files.createTempFile(root, "setups-", ".pending")
                try {
                    FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    beforeReplace(temporary)
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    knownBytes = bytes
                } finally {
                    // Cleanup cannot turn a completed replacement into reported failure.
                    try { Files.deleteIfExists(temporary) } catch (_: IOException) { /* An orphan is never read as a profile. */ }
                }
            }
        }
    }

    /** Waits for active file work, then prevents new operations; no persistent handles remain. */
    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { closed = true } }

    /** Coordinates instances without indefinite waits and preserves invalid files for manual recovery.
     * @param operation Bounded work while holding the per-user lock.
     * @return Operation result.
     * @throws IOException With a user-safe explanation for access or filesystem failures.
     */
    private fun <T> storageOperation(operation: (Path) -> T): T {
        try {
            val root = directory().toAbsolutePath().normalize()
            Files.createDirectories(root)
            return FileChannel.open(root.resolve("setups.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                checkNotNull(lock) { "Another AllConnect window is using saved setups. Try again when it finishes." }
                lock.use { operation(root) }
            }
        } catch (error: IOException) {
            throw IOException("Could not access or atomically replace saved setups. Check folder permissions and free space, then retry. The previous profile was not intentionally overwritten.", error)
        } catch (error: SecurityException) {
            throw IOException("Saved setup storage is not permitted. Check folder permissions, then retry.", error)
        }
    }

    /** @param path Exact profile path. @return Bounded bytes or null only for absence. */
    private fun readBounded(path: Path): ByteArray? = try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
            stream.readNBytes(MAX_PROFILE_BYTES + 1).also {
                require(it.size <= MAX_PROFILE_BYTES) { "Saved setups exceed the 1 MiB profile limit. Move the file aside, then retry loading." }
            }
        }
    } catch (_: NoSuchFileException) { null }
}

private const val PROFILE_FILE = "setups.acp"

/** @return Windows per-user storage path. @throws IllegalStateException If Windows LocalAppData is absent. */
private fun defaultProfileDirectory(): Path {
    val localAppData = System.getenv("LOCALAPPDATA")
    check(!localAppData.isNullOrBlank()) { "Windows LocalAppData is unavailable; saved setups cannot be loaded." }
    return Path.of(localAppData, "AllConnect")
}

/** @param first Current disk bytes or absence. @param second Last observed bytes or absence. @return Exact equality. */
private fun equalBytes(first: ByteArray?, second: ByteArray?): Boolean =
    if (first == null || second == null) first == null && second == null else first.contentEquals(second)
