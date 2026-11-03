package com.example.connectallspeakers

import com.example.connectallspeakers.data.profiles.*
import com.example.connectallspeakers.model.audio.*
import kotlin.test.*

/** Literal historical schema-one input, plus typed EQ round trips and malformed-data protection. */
class EqProfileMigrationTest {
    @Test fun versionOneMigratesToBypassedNeutralWithoutLosingRoomOrOptionalSettings() {
        val old = "ALLCONNECT-PROFILES\t1\n" +
            "W\tmode=Real\n" +
            "B\tmode=Real\tselected=room\trooms=1\n" +
            "R\tid=room\tname=Вітальня\tselected=card\tsnap=false\tzoom=1.25\treference=card\tdrift=true\tcards=1\n" +
            "S\tid=card\tname=Custom speaker\tchannel=Right\tvolume=37\tx=0.7\ty=0.6\thomeX=0.8\thomeY=0.1\tendpoint=stable-id\tendpointName=Same name\tdelayMs=0\text.future=keep\n" +
            "B\tmode=Demo\tselected=\trooms=0\nEND\n"
        val migrated = SetupProfileCodec.decode(old.encodeToByteArray())
        val room = migrated.real.rooms.single(); val card = room.speakers.single()
        assertEquals(SpeakerEq(), card.eq)
        assertEquals("stable-id", card.endpointId)
        assertEquals("Custom speaker", card.name)
        assertEquals(37, card.volume)
        assertEquals(.8f, card.homePosition.x)
        assertEquals(1.25f, room.zoom)
        assertEquals("keep", card.extensions["ext.future"])
        assertTrue(room.driftCorrectionEnabled)
        val rewritten = SetupProfileCodec.encode(migrated)
        assertTrue(rewritten.decodeToString().startsWith("ALLCONNECT-PROFILES\t2\n"))
        assertEquals(migrated, SetupProfileCodec.decode(rewritten))
    }

    @Test fun independentTypedEqAndUnknownExtensionsSurviveRoundTripAndEditing() {
        val base = profileFixture()
        val room = base.real.rooms.single()
        val eq = SpeakerEq(true, EqBand(80.0, 6.0, .3), EqBand(1200.0, -12.0, 2.0), EqBand(16000.0, 12.0, 1.5))
        val changed = room.copy(speakers = room.speakers.mapIndexed { index, card ->
            if (index == 1) card.copy(eq = eq, extensions = mapOf("ext.future" to "retained")) else card })
        val profile = base.copy(real = base.real.copy(rooms = listOf(changed)))
        val decoded = SetupProfileCodec.decode(SetupProfileCodec.encode(profile))
        assertEquals(profile, decoded)
        val edited = decoded.real.rooms.single().toRoom().copy(name = "Edited").toProfile(changed)
        assertEquals(eq, edited.speakers.last().eq)
        assertEquals("retained", edited.speakers.last().extensions["ext.future"])
        assertEquals(SpeakerEq(), edited.speakers.first().eq)
    }

    @Test fun malformedOrMissingTypedEqNeverActivatesInvalidFiltersOrSilentlyDefaults() {
        val valid = SetupProfileCodec.encode(profileFixture()).decodeToString()
        for (bad in listOf(valid.replace("eq1Hz=120.0", "eq1Hz=NaN"), valid.replace("eq1Db=0.0", "eq1Db=Infinity"),
            valid.replace("eq1Q=1.0", "eq1Q=0.0"), valid.replace("eq1Hz=120.0", "eq1Hz=16001.0"),
            valid.replace("eq1Db=0.0", "eq1Db=-12.01"), valid.replace("eqEnabled=false", "eqEnabled=yes"),
            valid.replace("\teq1Q=1.0", ""), valid.replace("PROFILES\t2", "PROFILES\t3"))) {
            assertFailsWith<IllegalArgumentException> { SetupProfileCodec.decode(bad.encodeToByteArray()) }
        }
    }
}
