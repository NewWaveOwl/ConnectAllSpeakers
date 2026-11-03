package com.example.connectallspeakers.data.profiles

import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.profiles.*

/** Small, dependency-free UTF-8 record format, version 1. Fields use named keys and escaped values.
 * Strict framing/counts reject truncation; optional ext. fields survive older-editor round trips.
 * Required semantic changes need a new version and an explicit migration before opening it.
 */
object SetupProfileCodec {
    /** Validates and serializes settings; callers own the returned bytes.
     * @param profile Durable settings, with no live state.
     * @return At most [MAX_PROFILE_BYTES] strict UTF-8 bytes.
     * @throws IllegalArgumentException For invalid settings or oversized output.
     */
    fun encode(profile: SetupProfile): ByteArray {
        profile.validate()
        val text = buildString {
            append("ALLCONNECT-PROFILES\t1\n")
            record("W", mapOf("mode" to profile.mode.name))
            for ((mode, bank) in listOf(ProfileMode.Real to profile.real, ProfileMode.Demo to profile.demo)) {
                record("B", mapOf("mode" to mode.name, "selected" to bank.selectedRoomId.orEmpty(), "rooms" to bank.rooms.size.toString()))
                for (room in bank.rooms) {
                    record("R", mapOf("id" to room.id, "name" to room.name, "selected" to room.selectedSpeakerId.orEmpty(),
                        "snap" to room.snapToGrid.toString(), "zoom" to room.zoom.toString(),
                        "reference" to room.referenceSpeakerId.orEmpty(), "drift" to room.driftCorrectionEnabled.toString(),
                        "cards" to room.speakers.size.toString()) + room.extensions)
                    for (speaker in room.speakers) record("S", mapOf("id" to speaker.id, "name" to speaker.name,
                        "channel" to speaker.channel.name, "volume" to speaker.volume.toString(),
                        "x" to speaker.position.x.toString(), "y" to speaker.position.y.toString(),
                        "homeX" to speaker.homePosition.x.toString(), "homeY" to speaker.homePosition.y.toString(),
                        "endpoint" to speaker.endpointId.orEmpty(), "endpointName" to speaker.endpointName.orEmpty(),
                        "delayMs" to speaker.delayMillis.toString()) + speaker.extensions)
                }
            }
            append("END\n")
        }
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= MAX_PROFILE_BYTES) { "Saved setups exceed the 1 MiB profile limit." }
        return bytes
    }

    /** Parses a complete supported profile, failing closed rather than repairing partial content.
     * @param bytes Caller-owned bounded input, not retained.
     * @return Validated independent settings; extensions are copied into read-only maps.
     * @throws IllegalArgumentException For malformed UTF-8/framing/values or unsupported versions.
     */
    fun decode(bytes: ByteArray): SetupProfile {
        require(bytes.size <= MAX_PROFILE_BYTES) { "Saved setups exceed the 1 MiB profile limit." }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(text.endsWith('\n')) { "Saved setups are truncated (missing end marker)." }
        val lines = text.dropLast(1).split('\n')
        require(lines.size <= 10_000 && lines.first().startsWith("ALLCONNECT-PROFILES\t")) { "Invalid saved setup format." }
        require(lines.first() == "ALLCONNECT-PROFILES\t1") { "Unsupported saved setup version. Update the app or move this file aside before retrying." }
        var cursor = 1
        fun read(kind: String): Fields {
            require(cursor < lines.lastIndex) { "Saved setups are truncated." }
            val parts = lines[cursor++].split('\t')
            require(parts.first() == kind && parts.size <= 64) { "Invalid saved setup record." }
            val fields = mutableMapOf<String, String>()
            for (field in parts.drop(1)) {
                val separator = field.indexOf('=')
                require(separator > 0) { "Invalid saved setup field." }
                val key = field.substring(0, separator)
                require(key !in fields) { "Duplicate saved setup field." }
                fields[key] = unescape(field.substring(separator + 1))
            }
            return Fields(fields)
        }
        val workspace = read("W")
        val mode = enumValueOf<ProfileMode>(workspace.text("mode"))
        workspace.finish()
        fun bank(expected: ProfileMode): ProfileBank {
            val fields = read("B")
            require(fields.text("mode") == expected.name) { "Missing or duplicate saved workspace bank." }
            val selected = fields.optional("selected")
            val count = fields.count("rooms")
            fields.finish()
            val rooms = List(count) {
                val room = read("R")
                val id = room.text("id"); val name = room.text("name"); val selectedCard = room.optional("selected")
                val snap = room.bool("snap"); val zoom = room.number("zoom")
                val reference = room.optional("reference"); val drift = room.bool("drift")
                val cards = room.count("cards"); val extras = room.finish(allowExtensions = true)
                val speakers = List(cards) {
                    val card = read("S")
                    val cardId = card.text("id"); val cardName = card.text("name")
                    val channel = enumValueOf<SpeakerChannel>(card.text("channel")); val volume = card.integer("volume")
                    val position = RoomPosition(card.number("x"), card.number("y"))
                    val home = RoomPosition(card.number("homeX"), card.number("homeY"))
                    val endpoint = card.optional("endpoint"); val endpointName = card.optional("endpointName")
                    val delay = card.integer("delayMs")
                    SpeakerProfile(cardId, cardName, channel, volume, position, home, endpoint, endpointName, delay,
                        card.finish(allowExtensions = true))
                }
                RoomProfile(id, name, speakers, selectedCard, snap, zoom, reference, drift, extras)
            }
            return ProfileBank(rooms, selected)
        }
        val real = bank(ProfileMode.Real); val demo = bank(ProfileMode.Demo)
        require(cursor == lines.lastIndex && lines[cursor] == "END") { "Invalid end marker or unexpected saved setup records." }
        return SetupProfile(real, demo, mode).also { it.validate() }
    }

    /** @param kind Record kind. @param fields Keys and unescaped values to append. */
    private fun StringBuilder.record(kind: String, fields: Map<String, String>) {
        append(kind)
        fields.forEach { (key, value) -> append('\t'); append(key); append('='); append(escape(value)) }
        append('\n')
        require(length <= MAX_PROFILE_BYTES) { "Saved setups exceed the 1 MiB profile limit." }
    }

    /** @param value Unescaped field value. @return Reversible framing-safe text. */
    private fun escape(value: String): String = buildString {
        require('\u0000' !in value) { "Invalid control character in saved setups." }
        value.forEach { append(when (it) { '\\' -> "\\\\"; '\t' -> "\\t"; '\n' -> "\\n"; '\r' -> "\\r"; else -> it.toString() }) }
    }

    /** @param value Encoded field. @return Original value. @throws IllegalArgumentException For invalid escapes. */
    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            require(char != '\r' && char != '\u0000') { "Invalid control character in saved setups." }
            if (char != '\\') append(char) else {
                require(index < value.length) { "Truncated saved setup escape." }
                append(when (value[index++]) { '\\' -> '\\'; 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> throw IllegalArgumentException("Invalid saved setup escape.") })
            }
        }
    }
}

/** Destructive local field reader, never shared beyond a single decode operation.
 * @property values Unconsumed keys; required fields cannot silently default.
 */
private class Fields(private val values: MutableMap<String, String>) {
    /** @param key Required key. @return Exact value. */
    fun text(key: String): String = requireNotNull(values.remove(key)) { "Missing saved setup field: $key." }
    /** @param key Required nullable key. @return Null for the explicit empty representation. */
    fun optional(key: String): String? = text(key).ifEmpty { null }
    /** @param key Integer key. @return Strict integer value. */
    fun integer(key: String): Int = requireNotNull(text(key).toIntOrNull()) { "Invalid saved integer: $key." }
    /** @param key Count key. @return Bounded record count. */
    fun count(key: String): Int = integer(key).also { require(it in 0..64) { "Too many saved $key." } }
    /** @param key Float key. @return Parsed value, subsequently checked for finite supported bounds. */
    fun number(key: String): Float = requireNotNull(text(key).toFloatOrNull()) { "Invalid saved coordinate: $key." }
    /** @param key Boolean key. @return Strict boolean. */
    fun bool(key: String): Boolean = requireNotNull(text(key).toBooleanStrictOrNull()) { "Invalid saved preference: $key." }
    /** @param allowExtensions Whether optional ext. keys are supported. @return Independent remaining fields. */
    fun finish(allowExtensions: Boolean = false): Map<String, String> {
        require(values.isEmpty() || (allowExtensions && values.keys.all { it.startsWith("ext.") })) { "Unknown required profile fields; update the app before saving." }
        return values.toMap()
    }
}
