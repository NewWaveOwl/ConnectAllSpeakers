package com.example.connectallspeakers.model.audio

/** First live-input wire format; Windows capture conversion is validated before data enters the engine. */
const val LIVE_SAMPLE_RATE = 48_000
/** Interleaved left/right channels in the bounded live-source contract. */
const val LIVE_CHANNELS = 2

/** Continuous-source lifecycle; idle has no pending recording or playback backlog. */
enum class LiveInputPhase { Buffering, Forwarding, Idle }

/** Copied live diagnostics, excluding PCM and native resources.
 * @property endpointId Exact source identity: owned virtual endpoint or session-only process/creation identity; never a default alias.
 * @property phase Buffering/forwarding/idle state of the explicit live session.
 * @property bufferedFrames Retained source frames, bounded to two seconds.
 * @property inputFrames Accepted frames in the current input epoch.
 * @property correctionPpm Per-target source/output rate corrections, in parts per million.
 * @property message Readable status; engine queues do not establish acoustic latency.
 */
data class LiveInputStatus(val endpointId: String, val phase: LiveInputPhase, val bufferedFrames: Int = 0,
    val inputFrames: Long = 0, val correctionPpm: Map<String, Double> = emptyMap(), val message: String? = null)
