package com.example.connectallspeakers.audio.input.windows

import com.example.connectallspeakers.audio.input.LiveCaptureSource
import com.example.connectallspeakers.audio.input.LivePcmConsumer
import com.example.connectallspeakers.model.audio.CaptureApp
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** Ordinary user-mode activation DLL. No driver/service installation or Windows default changes. */
internal interface ProcessActivationApi : Library {
    fun ac_activate(processId: Int, startedAtMillis: Long, client: PointerByReference): Int
}

/** Process tree selection excludes this application, its children and all its ancestors to prevent feedback. */
internal object ProcessAudioApps {
    private val activation by lazy { Native.load("AllConnectProcessCapture", ProcessActivationApi::class.java) }
    fun activate(app: CaptureApp, result: PointerByReference): Int = try {
        activation.ac_activate(app.processId.toInt(), app.startedAtMillis, result)
    } catch (failure: LinkageError) {
        throw IllegalStateException("The Windows x64 app-capture component could not load. Rebuild or reinstall the desktop app.", failure)
    }

    /** Snapshot of readable running executables; nothing starts capture and paths never enter shared UI state. */
    fun list(): List<CaptureApp> = ProcessHandle.allProcesses().use { processes ->
        processes.map { process ->
            val info = process.info()
            val command = info.command().orElse(null)
            val started = info.startInstant().orElse(null)
            if (command == null || started == null || !allowed(process)) null
            else CaptureApp(process.pid(), started.toEpochMilli(), File(command).name)
        }.filter { it != null }.toList().filterNotNull().sortedWith(compareBy({ it.name.lowercase() }, { it.processId }))
    }

    /** Rechecks exact process identity before opening and every poll; exiting never selects a replacement. */
    fun validate(app: CaptureApp): ProcessHandle {
        require(app.processId in 1..0xffff_ffffL) { "Invalid selected app." }
        val process = ProcessHandle.of(app.processId).orElseThrow { IllegalStateException("The selected app has exited. Refresh apps and select it again.") }
        check(process.isAlive && process.info().startInstant().orElse(null)?.toEpochMilli() == app.startedAtMillis) { "The selected app has exited or restarted. Select it again." }
        require(allowed(process)) { "AllConnect, its child processes and its parent processes cannot be captured." }
        return process
    }

    private fun allowed(process: ProcessHandle): Boolean {
        val self = ProcessHandle.current()
        if (process.pid() == self.pid()) return false
        return !hasAncestor(self, process.pid()) && !hasAncestor(process, self.pid())
    }

    private fun hasAncestor(process: ProcessHandle, ancestorId: Long): Boolean {
        var current = process
        val visited = mutableSetOf<Long>()
        repeat(256) {
            val parent = current.parent().orElse(null) ?: return false
            if (parent.pid() == ancestorId || !visited.add(parent.pid())) return true
            current = parent
        }
        return true // An unresolved/cyclic ancestry cannot establish a safe process tree.
    }
}

/** Wraps the shared packet reader with process-exit detection; all methods belong to the audio worker. */
internal class ProcessAudioCapture(private val app: CaptureApp) : LiveCaptureSource {
    private val process = ProcessAudioApps.validate(app)
    private val reader = WasapiLoopbackCapture("process:${app.processId}", app)
    override fun start() { checkAlive(); reader.start() }
    override fun poll(consumer: LivePcmConsumer): Int { checkAlive(); return reader.poll(consumer) }
    override fun close() = reader.close()
    private fun checkAlive() { check(process.isAlive) { "The selected app exited. Capture stopped; select an app to restart." } }
}
