package com.example.connectallspeakers

import com.example.connectallspeakers.audio.input.windows.ProcessActivationApi
import com.example.connectallspeakers.audio.input.windows.ProcessAudioApps
import com.example.connectallspeakers.model.audio.CaptureApp
import com.sun.jna.Native
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Exercises the bundled DLL and exclusion boundary without initializing COM or capturing any audio. */
class ProcessActivationBoundaryTest {
    @Test fun bundledNativeBridgeRejectsOwnProcessAndInvalidPidWithoutOpeningAudio() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val api = Native.load("AllConnectProcessCapture", ProcessActivationApi::class.java)
        for (pid in listOf(0, ProcessHandle.current().pid().toInt())) {
            val result = PointerByReference()
            assertTrue(api.ac_activate(pid, 0, result) < 0)
            assertNull(result.value)
        }
    }

    @Test fun selectedAppValidationRejectsAllConnectItself() {
        val self = ProcessHandle.current()
        assertFailsWith<IllegalArgumentException> {
            ProcessAudioApps.validate(CaptureApp(self.pid(), self.info().startInstant().get().toEpochMilli(), "AllConnect"))
        }
    }
}
