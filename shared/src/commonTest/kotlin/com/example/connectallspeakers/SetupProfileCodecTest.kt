package com.example.connectallspeakers

import com.example.connectallspeakers.data.profiles.*
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.profiles.*
import kotlin.test.*

/** Durable schema validation/evolution using portable bytes only. */
class SetupProfileCodecTest {
    @Test fun unicodeEscapesDuplicateDisplayNamesAndAllSettingsRoundTrip() {
        val profile = profileFixture()
        assertEquals(profile, SetupProfileCodec.decode(SetupProfileCodec.encode(profile)))
        val text = SetupProfileCodec.encode(profile).decodeToString()
        for (excluded in listOf("isDefault", "availability", "clock", "buffer", "audioFile", "positionTicks")) assertFalse(text.contains(excluded))
        assertEquals(listOf("endpoint-a", "endpoint-b"), profile.real.rooms.single().speakers.map { it.endpointId })
    }

    @Test fun validationRejectsInvalidVolumesDelaysCoordinatesZoomAndNames() {
        val original = profileFixture()
        val room = original.real.rooms.single()
        val card = room.speakers.last()
        for (invalid in listOf(card.copy(volume = -1), card.copy(volume = 101), card.copy(delayMillis = -1),
            card.copy(delayMillis = 501), card.copy(position = RoomPosition(Float.NaN, .2f)),
            card.copy(homePosition = RoomPosition(.2f, Float.POSITIVE_INFINITY)),
            card.copy(position = RoomPosition(-.01f, .2f)), card.copy(homePosition = RoomPosition(.2f, 1.01f)),
            card.copy(name = " "), card.copy(name = "a".repeat(81)), card.copy(endpointId = ""))) {
            assertFailsWith<IllegalArgumentException> {
                SetupProfileCodec.encode(original.copy(real = original.real.copy(rooms = listOf(room.copy(speakers = listOf(room.speakers.first(), invalid))))))
            }
        }
        for (zoom in listOf(Float.NaN, Float.NEGATIVE_INFINITY, .74f, 1.51f)) assertFailsWith<IllegalArgumentException> {
            original.copy(real = original.real.copy(rooms = listOf(room.copy(zoom = zoom)))).validate()
        }
        assertFailsWith<IllegalArgumentException> { original.copy(real = original.real.copy(rooms = listOf(room.copy(name = "")))).validate() }
    }

    @Test fun identitySelectionAndCalibrationErrorsNeverSilentlyRepairOrRedirect() {
        val original = profileFixture()
        val room = original.real.rooms.single()
        val a = room.speakers.first(); val b = room.speakers.last()
        val rooms = listOf(room.copy(speakers = listOf(a, b.copy(id = a.id))),
            room.copy(speakers = listOf(a, b.copy(endpointId = a.endpointId))),
            room.copy(selectedSpeakerId = "missing"), room.copy(referenceSpeakerId = "missing"),
            room.copy(speakers = listOf(a.copy(delayMillis = 1), b)),
            room.copy(id = a.id))
        rooms.forEach { assertFailsWith<IllegalArgumentException> { original.copy(real = ProfileBank(listOf(it), it.id)).validate() } }
        assertFailsWith<IllegalArgumentException> { original.copy(real = ProfileBank(listOf(room, room), room.id)).validate() }
        assertFailsWith<IllegalArgumentException> { original.copy(real = original.real.copy(selectedRoomId = "missing")).validate() }
        val secondRoom = room.copy(id = "other")
        assertFailsWith<IllegalArgumentException> { original.copy(real = ProfileBank(listOf(room, secondRoom), room.id)).validate() }
    }

    @Test fun demoHardwareMixingIsRejectedButBanksMayUseTheSameRoomIdentity() {
        val original = profileFixture()
        val real = original.real.rooms.single()
        assertFailsWith<IllegalArgumentException> { original.copy(demo = ProfileBank(listOf(real), real.id)).validate() }
        val demo = original.demo.rooms.single()
        assertEquals(original.real.rooms.single().id, demo.id)
        assertTrue(demo.speakers.all { it.endpointId == null })
        assertFailsWith<IllegalArgumentException> { original.copy(demo = ProfileBank(listOf(demo.copy(driftCorrectionEnabled = true)), demo.id)).validate() }
    }

    @Test fun malformedTruncatedOversizedAndUnsupportedFilesFailClosed() {
        val valid = SetupProfileCodec.encode(profileFixture()).decodeToString()
        val malformed = listOf("", valid.dropLast(1), valid.substringBefore("END"),
            valid.replace("ALLCONNECT-PROFILES\t2", "ALLCONNECT-PROFILES\t99"),
            valid.replace("volume=37", "volume=nan"), valid.replace("x=0.7", "x=NaN"),
            valid.replace("delayMs=123", "delayMs=501"), valid.replace("snap=false", "snap=perhaps"),
            valid.replace("cards=2", "cards=3"), valid.replace("rooms=1", "rooms=999999999999"),
            valid.replace("channel=Right", "channel=Surround"), valid.replace("volume=37", "volume=37\tvolume=20"),
            valid.replace("id=card-b", "id=card-a"), valid.replace("endpoint=endpoint-b", "endpoint=endpoint-a"),
            valid.replace("volume=37", "volume=37\tfutureRequired=1"), valid.replace("name=Лівий", "name=\\qЛівий"),
            valid + "END\n")
        malformed.forEach { assertFails("Should reject malformed profile") { SetupProfileCodec.decode(it.encodeToByteArray()) } }
        assertFails { SetupProfileCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { SetupProfileCodec.decode(ByteArray(MAX_PROFILE_BYTES + 1)) }
        val tooMany = profileFixture().copy(real = ProfileBank(List(65) { RoomProfile("r$it", "Room") }, "r0"))
        assertFails { tooMany.validate() }
    }

    @Test fun versionOneOptionalEqFieldsSurviveOlderEditorChangesWithoutLosingSettings() {
        val original = profileFixture()
        val futureBytes = SetupProfileCodec.encode(original).decodeToString()
            .replace("volume=37", "ext.eq.lowGainDb=-2.5\tvolume=37").encodeToByteArray()
        val withFutureEq = SetupProfileCodec.decode(futureBytes)
        val old = withFutureEq.real.rooms.single()
        val edited = old.toRoom().copy(name = "Edited room", speakers = old.toRoom().speakers.map { it.copy(volume = 42) }).toProfile(old)
        val saved = SetupProfileCodec.decode(SetupProfileCodec.encode(withFutureEq.copy(real = ProfileBank(listOf(edited), edited.id))))
        val card = saved.real.rooms.single().speakers.last()
        assertEquals("-2.5", card.extensions["ext.eq.lowGainDb"])
        assertEquals(42, card.volume)
        assertEquals(old.speakers.last().position, card.position)
        assertEquals(123, card.delayMillis)
        assertEquals(original.demo, saved.demo)
        assertEquals("Edited room", saved.real.rooms.single().name)
    }

    @Test fun intentionallyEmptyBanksRoundTripWithoutInventingDefaultRooms() {
        val empty = SetupProfile(mode = ProfileMode.Demo)
        assertEquals(empty, SetupProfileCodec.decode(SetupProfileCodec.encode(empty)))
    }

    @Test fun oversizedSavesAndInvalidExtensionsAreRejectedBeforeStorage() {
        val original = profileFixture()
        val room = original.real.rooms.single()
        val card = room.speakers.first()
        val extensions = (1..32).associate { "ext.future$it" to "x".repeat(4096) }
        val oversized = room.copy(speakers = List(9) { card.copy(id = "big-$it", endpointId = "endpoint-$it", extensions = extensions) },
            selectedSpeakerId = "big-0", referenceSpeakerId = "big-0")
        assertFailsWith<IllegalArgumentException> { SetupProfileCodec.encode(SetupProfile(real = ProfileBank(listOf(oversized), oversized.id))) }
        for (invalid in listOf(mapOf("unknown" to "1"), mapOf("ext." to "1"), mapOf("ext.eq" to "x".repeat(4097)))) {
            assertFailsWith<IllegalArgumentException> { original.copy(real = ProfileBank(listOf(room.copy(extensions = invalid)), room.id)).validate() }
        }
    }
}

/** @return Independent, fully populated real/demo durable fixtures without native state. */
internal fun profileFixture(): SetupProfile {
    val first = SpeakerProfile("card-a", "Лівий \\ 左\n=\t", SpeakerChannel.Left, 80,
        RoomPosition(.2f, .3f), RoomPosition(.1f, .1f), "endpoint-a", "Same name")
    val second = SpeakerProfile("card-b", "Right", SpeakerChannel.Right, 37,
        RoomPosition(.7f, .6f), RoomPosition(.8f, .1f), "endpoint-b", "Same name", 123)
    val room = RoomProfile("room", "Вітальня 🦉", listOf(first, second), second.id, false, 1.25f, first.id, true)
    val sample = first.copy(id = "sample-a", endpointId = null, endpointName = null)
    val demo = RoomProfile("room", "Demo room", listOf(sample), sample.id)
    return SetupProfile(ProfileBank(listOf(room), room.id), ProfileBank(listOf(demo), demo.id))
}
