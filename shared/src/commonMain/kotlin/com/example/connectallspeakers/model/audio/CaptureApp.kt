package com.example.connectallspeakers.model.audio

/** Session-only process identity; creation time prevents accidentally capturing a reused PID.
 * @property processId Windows PID, never a saved profile route.
 * @property startedAtMillis Process creation time as Unix epoch milliseconds.
 * @property name Executable label, with PID shown separately for duplicate names.
 */
data class CaptureApp(val processId: Long, val startedAtMillis: Long, val name: String)
