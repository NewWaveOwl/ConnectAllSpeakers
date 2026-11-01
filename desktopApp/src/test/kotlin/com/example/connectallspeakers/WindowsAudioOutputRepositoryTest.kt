package com.example.connectallspeakers

import com.example.connectallspeakers.audio.windows.WindowsAudioBackend
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.data.audio.windows.WindowsAudioOutputRepository
import com.example.connectallspeakers.model.audio.AudioOutputDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Worker/lifetime acceptance tests against controlled native boundaries, never live hardware. */
class WindowsAudioOutputRepositoryTest {
    @Test
    fun notificationsRefreshMetadataAndCloseUnregistersOnTheSameWorker() = runBlocking {
        val backend = ControlledBackend()
        val repository = WindowsAudioOutputRepository { backend }
        try {
            withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready } }
            backend.devices = listOf(AudioOutputDevice("same-a", "Same name"), AudioOutputDevice("same-b", "Same name", isDefault = true))
            backend.changed!!()
            val changed = withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready && it.devices.size == 2 } }
            assertEquals("same-b", changed.devices.first().endpointId)
            backend.devices = emptyList()
            backend.changed!!()
            withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready && it.devices.isEmpty() } }
        } finally { repository.close(); withTimeout(5000) { repository.awaitClosed() } }
        repository.close()
        assertEquals(1, backend.closes.get())
        assertEquals(1, backend.threadIds.toSet().size)
        assertTrue(backend.threads.all { it.startsWith("Windows-CoreAudio-discovery") })
    }

    @Test
    fun enumerationFailureRetainsLastListAndManualRefreshRecovers() = runBlocking {
        val backend = ControlledBackend()
        val repository = WindowsAudioOutputRepository { backend }
        try {
            val initial = withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready } }
            backend.failNext.set(true)
            repository.refresh()
            val error = withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Error } }
            assertEquals(initial.devices, error.devices)
            assertTrue(error.message!!.contains("Controlled enumeration failure"))
            repository.refresh()
            val recovered = withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready && it.revision > initial.revision } }
            assertEquals(initial.devices, recovered.devices)
        } finally { repository.close(); withTimeout(5000) { repository.awaitClosed() } }
    }

    @Test
    fun initializationFailureCleansTheCandidateAndRetriesWithoutLeakingWorkers() = runBlocking {
        val failed = ControlledBackend(failOpen = true)
        val success = ControlledBackend()
        val attempts = AtomicInteger()
        val repository = WindowsAudioOutputRepository { if (attempts.getAndIncrement() == 0) failed else success }
        try {
            withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Error } }
            assertEquals(1, failed.closes.get())
            repository.refresh()
            withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready } }
        } finally { repository.close(); withTimeout(5000) { repository.awaitClosed() } }
        assertEquals(1, success.closes.get())
    }

    @Test
    fun burstCallbacksAreNonblockingAndCoalesceWhileEnumerationIsBusy() = runBlocking {
        val backend = ControlledBackend()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.beforeEnumerate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val repository = WindowsAudioOutputRepository { backend }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1000) { backend.changed!!() }
            backend.beforeEnumerate = null
            release.countDown()
            withTimeout(5000) { repository.outputs.first { it.status == AudioOutputStatus.Ready && it.revision == 2L } }
            assertEquals(2, backend.enumerations.get())
        } finally { release.countDown(); repository.close(); withTimeout(5000) { repository.awaitClosed() } }
    }

    /** Native fake that captures lifecycle threading and supports explicit failures and queue bursts. */
    private class ControlledBackend(private val failOpen: Boolean = false) : WindowsAudioBackend {
        @Volatile var devices = listOf(AudioOutputDevice("initial", "Controlled output"))
        @Volatile var changed: (() -> Unit)? = null
        @Volatile var beforeEnumerate: (() -> Unit)? = null
        val failNext = AtomicBoolean(false)
        val enumerations = AtomicInteger()
        val closes = AtomicInteger()
        val threads = CopyOnWriteArrayList<String>()
        val threadIds = CopyOnWriteArrayList<Long>()
        override fun open(onChanged: () -> Unit) {
            threads.add(Thread.currentThread().name)
            threadIds.add(Thread.currentThread().threadId())
            changed = onChanged
            if (failOpen) error("Controlled initialization failure")
        }
        override fun enumerate(): List<AudioOutputDevice> {
            threads.add(Thread.currentThread().name)
            threadIds.add(Thread.currentThread().threadId())
            enumerations.incrementAndGet()
            beforeEnumerate?.invoke()
            if (failNext.compareAndSet(true, false)) error("Controlled enumeration failure")
            return devices
        }
        override fun close() {
            threads.add(Thread.currentThread().name); threadIds.add(Thread.currentThread().threadId())
            closes.incrementAndGet(); changed = null
        }
    }
}
