package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt
import kotlin.test.*

/** Real codec decoding of original fixtures, without player/Java Sound/native output APIs. */
class Mp3FileDecoderTest {
    @get:Rule val folder = TemporaryFolder()
    private val decoder = Mp3FileDecoder()
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/audio/$name")!!.use { it.readBytes() }
    private fun file(bytes: ByteArray, name: String = "source.mp3"): File = folder.newFolder().resolve(name).apply { writeBytes(bytes) }
    private fun decode(name: String): DecodedAudio = decoder.decode(file(fixture(name)).path) { false }
    private fun rms(audio: DecodedAudio, channel: Int, startSeconds: Double, endSeconds: Double): Double {
        val start = (startSeconds * audio.sampleRate).toInt()
        val end = (endSeconds * audio.sampleRate).toInt()
        return sqrt((start until end).sumOf { frame -> audio.samples[frame * audio.channels + channel].toDouble().let { it * it } } / (end - start))
    }

    @Test fun stereoCbrPreservesRateChannelsAndLeftRightIdentity() {
        val audio = decode("stereo-cbr-44100.mp3")
        assertEquals(44_100, audio.sampleRate)
        assertEquals(2, audio.channels)
        assertTrue(audio.description.durationMillis in 1_000..1_300)
        assertTrue(rms(audio, 0, .1, .25) > .03)
        assertTrue(rms(audio, 1, .1, .25) < .001)
        assertTrue(rms(audio, 1, .45, .55) > .03)
        assertTrue(rms(audio, 0, .45, .55) < .001)
    }
    @Test fun vbrStereoAndLowerRateMonoDecodeToFiniteBoundedPcm() {
        listOf("stereo-vbr-48000.mp3" to (48_000 to 2), "mono-cbr-22050.mp3" to (22_050 to 1),
            "mono-cbr-8000.mp3" to (8_000 to 1)).forEach { (name, format) ->
            val audio = decode(name)
            assertEquals(format.first, audio.sampleRate)
            assertEquals(format.second, audio.channels)
            assertTrue(audio.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(rms(audio, 0, .7, .9) > .01)
            assertTrue(audio.description.durationMillis in 1_000..1_400)
        }
    }
    @Test fun id3v2HeadersFooterAndId3v1AreSkippedWithoutChangingPcm() {
        val original = fixture("stereo-cbr-44100.mp3")
        val expected = decoder.decode(file(original).path) { false }
        val header = byteArrayOf(73, 68, 51, 4, 0, 16, 0, 0, 0, 4)
        val footer = header.copyOf().apply { this[0] = 51; this[1] = 68; this[2] = 73 }
        val legacy = ByteArray(128).apply { this[0] = 84; this[1] = 65; this[2] = 71 }
        val tagged = header + ByteArray(4) + footer + original + legacy
        assertContentEquals(expected.samples, decoder.decode(file(tagged).path) { false }.samples)
        val simpleTag = byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, 0)
        assertContentEquals(expected.samples, decoder.decode(file(simpleTag + original + simpleTag).path) { false }.samples)
    }
    @Test fun oversizedTruncatedAndInvalidMetadataFailBeforeTagAllocation() {
        listOf(
            byteArrayOf(73, 68, 51, 4, 0, 0, 127, 127, 127, 127),
            byteArrayOf(73, 68, 51, 4, 0, 0, 128.toByte(), 0, 0, 0),
            byteArrayOf(73, 68, 51, 4),
        ).forEach { bytes -> assertFailsWith<AudioFileException> { decoder.decode(file(bytes).path) { false } } }
    }
    @Test fun incompleteFramesOtherLayersFreeBitrateAndRandomDataAreRejected() {
        val valid = fixture("stereo-cbr-44100.mp3")
        val otherLayer = valid.copyOf().apply { this[1] = 0xFD.toByte() }
        val freeBitrate = valid.copyOf().apply { this[2] = (this[2].toInt() and 15).toByte() }
        listOf(valid.copyOf(valid.size - 1), otherLayer, freeBitrate, valid + byteArrayOf(1, 2), ByteArray(256)).forEach {
            assertFailsWith<AudioFileException> { decoder.decode(file(it).path) { false } }
        }
    }
    @Test fun concatenatedFormatChangesFailInsteadOfUsingWrongInterleaving() {
        val differentRate = fixture("stereo-cbr-44100.mp3") + fixture("stereo-vbr-48000.mp3")
        val differentChannels = fixture("mono-cbr-22050.mp3") + fixture("stereo-cbr-44100.mp3")
        listOf(differentRate, differentChannels).forEach {
            assertFailsWith<AudioFileException> { decoder.decode(file(it).path) { false } }
        }
    }
    @Test fun inputAndDecodedSizeLimitsRejectBeforePcmAllocation() {
        val oversized = file(byteArrayOf(0, 0, 0, 0))
        RandomAccessFile(oversized, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        assertFailsWith<AudioFileException> { decoder.decode(oversized.path) { false } }
        val valid = fixture("stereo-cbr-44100.mp3")
        // This original CBR fixture begins with a complete 417-byte MPEG frame.
        val repeated = file(byteArrayOf())
        repeated.outputStream().use { output -> repeat(15_000) { output.write(valid, 0, 417) } }
        val failure = assertFailsWith<AudioFileException> { decoder.decode(repeated.path) { false } }
        assertTrue(failure.message.orEmpty().contains("decoded PCM16"))
    }
    @Test fun cancellationDuringScanningAndDecodingPropagatesAndClosesFile() {
        val path = file(fixture("stereo-cbr-44100.mp3"))
        val layout = RandomAccessFile(path, "r").use { Mp3FrameLayout.inspect(it) { false } }
        assertFailsWith<CancellationException> { decoder.decode(path.path) { true } }
        var checks = 0
        assertFailsWith<CancellationException> { decoder.decode(path.path) { ++checks > layout.frameCount + 5 } }
        assertTrue(path.renameTo(path.resolveSibling("closed.mp3")))
    }
    @Test fun signatureDetectionIgnoresExtensionAndSanitizesReadFailures() {
        val path = file(fixture("stereo-cbr-44100.mp3"), "SOUND.WAV")
        assertEquals(2, LocalAudioFileDecoder().decode(path.path) { false }.channels)
        val missing = folder.root.resolve("private-file.mp3")
        val error = assertFailsWith<AudioFileException> { LocalAudioFileDecoder().decode(missing.path) { false } }
        assertFalse(error.message.orEmpty().contains(folder.root.path))
        assertFailsWith<AudioFileException> { LocalAudioFileDecoder().decode(file("not audio".toByteArray()).path) { false } }
    }
}
