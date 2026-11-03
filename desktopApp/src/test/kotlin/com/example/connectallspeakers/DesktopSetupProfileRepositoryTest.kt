package com.example.connectallspeakers

import com.example.connectallspeakers.data.profiles.*
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.profiles.*
import com.example.connectallspeakers.model.audio.EqBand
import com.example.connectallspeakers.model.audio.SpeakerEq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Uses isolated temporary files only. No launcher, discovery, playback or native endpoint is constructed. */
class DesktopSetupProfileRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root(): Path = temporary.root.toPath().resolve("AllConnect")
    private fun repository() = DesktopSetupProfileRepository { root() }
    private fun fixture(name: String = "Кімната 🦉"): SetupProfile {
        val a = SpeakerProfile("a", "Left", SpeakerChannel.Left, 24, RoomPosition(.1f, .2f), RoomPosition(.2f, .3f), "endpoint-a", "Same")
        val b = a.copy(id = "b", name = "Right", channel = SpeakerChannel.Right, endpointId = "endpoint-b", delayMillis = 125)
        return SetupProfile(real = ProfileBank(listOf(RoomProfile("room", name, listOf(a, b), "b", false, 1.3f, "a", true)), "room"))
    }

    @Test fun absentFileThenForcedAtomicReplacementSurvivesNewRepositoryAndClose() = runBlocking {
        val store = repository()
        assertNull(store.load())
        store.save(fixture())
        store.save(fixture("Second save")) // Exercises atomic replacement of an existing file on Windows.
        store.close()
        val reopened = repository()
        assertEquals(fixture("Second save"), reopened.load())
        assertEquals(listOf("endpoint-a", "endpoint-b"), reopened.load()!!.real.rooms.single().speakers.map { it.endpointId })
        reopened.close()
        assertFailsWith<IllegalStateException> { store.load() }
        assertFailsWith<IllegalStateException> { store.save(fixture()) }
        Unit
    }

    @Test fun interruptedSaveLeavesPreviousBytesAndRemovesOwnedTempFile() = runBlocking {
        val first = repository(); first.load(); first.save(fixture()); first.close()
        val original = Files.readAllBytes(root().resolve("setups.acp"))
        var temporaryPath: Path? = null
        val interrupted = DesktopSetupProfileRepository({ root() }) { path ->
            temporaryPath = path
            assertEquals(fixture("Not committed"), SetupProfileCodec.decode(Files.readAllBytes(path)))
            throw InterruptedIOException("Injected interruption after file flush")
        }
        interrupted.load()
        assertFailsWith<IOException> { interrupted.save(fixture("Not committed")) }
        assertContentEquals(original, Files.readAllBytes(root().resolve("setups.acp")))
        assertFalse(Files.exists(temporaryPath!!))
        assertEquals(fixture(), repository().load())
        interrupted.close()
    }

    @Test fun unsupportedAtomicReplacementNeverFallsBackToDestructiveWrite() = runBlocking {
        val first = repository(); first.load(); first.save(fixture()); first.close()
        val original = Files.readAllBytes(root().resolve("setups.acp"))
        val store = DesktopSetupProfileRepository({ root() }) { path -> throw AtomicMoveNotSupportedException(path.toString(), "setups.acp", "Injected") }
        store.load()
        assertFailsWith<IOException> { store.save(fixture("Must not replace")) }
        assertContentEquals(original, Files.readAllBytes(root().resolve("setups.acp")))
        assertEquals(fixture(), repository().load())
        store.close()
    }

    @Test fun malformedUnsupportedOversizedAndInvalidUtf8RemainUntouchedUntilManualRecovery() = runBlocking {
        Files.createDirectories(root())
        val path = root().resolve("setups.acp")
        val valid = SetupProfileCodec.encode(fixture())
        val badFiles = listOf(valid.copyOf(valid.size / 2), valid.decodeToString().replace("PROFILES\t2", "PROFILES\t99").encodeToByteArray(),
            ByteArray(MAX_PROFILE_BYTES + 1), byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            valid.decodeToString().replace("volume=24", "volume=999").encodeToByteArray())
        for (bad in badFiles) {
            Files.write(path, bad)
            val store = repository()
            assertFails { store.load() }
            assertFails { store.save(fixture("No overwrite")) }
            assertContentEquals(bad, Files.readAllBytes(path))
            store.close()
        }
        Files.move(path, root().resolve("setups-damaged.acp"))
        val recovered = repository(); assertNull(recovered.load()); recovered.save(fixture("Recovered")); recovered.close()
        assertEquals(fixture("Recovered"), repository().load())
        assertTrue(Files.exists(root().resolve("setups-damaged.acp")))
    }

    @Test fun staleWindowOrExternallyDamagedFileCannotOverwriteNewerData() = runBlocking {
        val first = repository(); val second = repository()
        first.load(); second.load()
        first.save(fixture())
        assertFailsWith<IllegalStateException> { second.save(fixture("Stale")) }
        second.load(); second.save(fixture("Newer"))
        assertFailsWith<IllegalStateException> { first.save(fixture("Old")) }
        assertEquals(fixture("Newer"), first.load())
        val invalid = "Truncated".encodeToByteArray()
        Files.write(root().resolve("setups.acp"), invalid)
        assertFailsWith<IllegalStateException> { first.save(fixture("Overwritten")) }
        assertContentEquals(invalid, Files.readAllBytes(root().resolve("setups.acp")))
        first.close(); second.close()
    }

    @Test fun invalidCandidateAndOrphanTempFileCannotDamageValidProfile() = runBlocking {
        val store = repository(); store.load(); store.save(fixture())
        Files.write(root().resolve("setups-orphan.pending"), "half-written garbage".encodeToByteArray())
        assertEquals(fixture(), store.load())
        val invalidRoom = fixture().real.rooms.single().copy(zoom = Float.NaN)
        assertFailsWith<IllegalArgumentException> { store.save(fixture().copy(real = ProfileBank(listOf(invalidRoom), "room"))) }
        assertEquals(fixture(), repository().load())
        store.close()
    }

    @Test fun unavailableStorageDirectoryIsRecoverableAndConstructorsDoNotTouchDisk() = runBlocking {
        val blocker = temporary.root.toPath().resolve("not-a-directory")
        Files.writeString(blocker, "keep")
        val store = DesktopSetupProfileRepository { blocker.resolve("AllConnect") }
        assertFailsWith<IOException> { store.load() }
        assertEquals("keep", Files.readString(blocker))
        assertFailsWith<IllegalStateException> { store.save(fixture()) }
        store.close()
    }

    @Test fun closeAwaitsActiveSaveAndAnotherInstanceReportsBusyWithoutBlocking() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val store = DesktopSetupProfileRepository({ root() }) {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Test timed out waiting to release file work" }
        }
        store.load()
        val save = async(Dispatchers.Default) { store.save(fixture()) }
        entered.await()
        try {
            assertFailsWith<IllegalStateException> { repository().load() }
            val close = async(Dispatchers.Default) { store.close() }
            assertFalse(close.isCompleted)
            release.countDown()
            save.await(); close.await()
            assertEquals(fixture(), repository().load())
        } finally { release.countDown() }
    }

    @Test fun oldProfileLoadsWithoutRewritingAndFailedEqUpgradePreservesOriginalBytes() = runBlocking {
        Files.createDirectories(root())
        val path = root().resolve("setups.acp")
        val old = ("ALLCONNECT-PROFILES\t1\nW\tmode=Real\nB\tmode=Real\tselected=\trooms=0\n" +
            "B\tmode=Demo\tselected=\trooms=0\nEND\n").encodeToByteArray()
        Files.write(path, old)
        val failure = DesktopSetupProfileRepository({ root() }) { throw IOException("Injected upgrade write failure") }
        assertEquals(SetupProfile(), failure.load())
        assertContentEquals(old, Files.readAllBytes(path))
        val base = fixture()
        val room = base.real.rooms.single()
        val upgraded = base.copy(real = base.real.copy(rooms = listOf(room.copy(speakers = room.speakers.map {
            it.copy(eq = SpeakerEq(true, mid = EqBand(1000.0, -6.0)))
        }))))
        assertFailsWith<IOException> { failure.save(upgraded) }
        assertContentEquals(old, Files.readAllBytes(path))
        failure.close()
        val store = repository(); store.load(); store.save(upgraded); store.close()
        assertTrue(Files.readString(path).startsWith("ALLCONNECT-PROFILES\t2\n"))
        assertEquals(upgraded, repository().load())
    }
}
