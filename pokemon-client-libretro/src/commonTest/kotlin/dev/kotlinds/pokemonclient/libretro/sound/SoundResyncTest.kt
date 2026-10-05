package dev.kotlinds.pokemonclient.libretro.sound

import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.ARM7_WRAM
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.MAIN_RAM
import dev.kotlinds.pokemonclient.libretro.Files
import dev.kotlinds.pokemonclient.libretro.environmentVariable
import dev.kotlinds.pokemonclient.libretro.readTestResource
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Music during pauses on real save states of HeartGold US (reduced fixtures, see [SoundFixtures], captured with the
 * bench's `pausemusicfixtures`): `_p` the paused game, `_s` the shadow's safe end 120 frames later, `_busy` a frame
 * where the ARM7 runs the sound thread, `_dma` (melonDS) a frame with a DMA mid-burst. `ds_cherry`: DeSmuME 0.9.12
 * in Cherrygrove City; `ds_battle_will` (Elite Four Will) and `ds_battle_wild` (a wild Hoothoot): DeSmuME battles at
 * the command menu, where HeartGold keeps the field music paused on another player while the battle music plays;
 * `ds_intro_cry` (DeSmuME, a wild battle's intro on Route 30: the battle music, a sound effect and the wild
 * Pokémon's cry playing at the pause; the shadow's end 600 frames later, music alone) and `ds_intro_new` (the same
 * intro earlier: the music alone at the pause, a sound effect started by the shadow playing at its end);
 * `ds_menu_wild` / `ds_menu_will` (DeSmuME, the wild battle and Will's battle paused the frame the command menu
 * appears, its sound effect playing; the shadow's end 10 s later, the battle music alone, past its loop point);
 * `ds_r13_shore` (DeSmuME, standing on Route 13's sea shore: the route music and the shore's looping ambient sound,
 * a map soundplate, both playing at the pause and 10 s later at the shadow's end), `ds_r13_enter` (just entered Route
 * 13 from Route 12's pier, the map name banner coming: the same, the shadow's end 5 s later) and `ds_r13_shore_new`
 * (paused mid-step on the shore: the shadow's game finishes the step onto the next soundplate and starts the ambient
 * sound again on another player);
 * `ds_r22_fade` (DeSmuME, the user's Route 22 case: a step into Route 22 from Viridian City, the route-name banner
 * coming, the game fading Viridian's music out to start Route 22's; the shadow's end 200 frames later, Route 22's
 * music started), `ds_r22_load` (the frame the game stopped Viridian's music and loads Route 22's, before it starts;
 * the shadow's end 90 frames later) and `ds_r22_new` (the pause put off until Route 22's music plays, as the app does;
 * the shadow's end 120 frames later); `ds_r1_gate_fade` (DeSmuME, just out of the Viridian / Route 1 gatehouse onto
 * Route 1, a warp: the gatehouse's music still fading out; the shadow's end 180 frames later, Route 1's music
 * started) and `ds_r1_gate_gap` (the frames between that fade-out and Route 1's music: no music; the shadow's end 100
 * frames later);
 * `mel`: melonDS 0.9.3.
 */
class SoundResyncTest {

    private val layout = SoundDriverLayout.HEARTGOLD_US

    private fun fixture(name: String): ByteArray =
        SoundFixtures.decode(readTestResource("sound/pausemusic_$name.state.gz"))

    private fun resync(splicer: SavestateSoundSplicer) = SoundResync(layout, splicer)

    private fun setArm7(state: ByteArray, splicer: SavestateSoundSplicer, address: Int, value: Int) {
        val offset = splicer.locate(state).arm7Wram.offset + address - ARM7_WRAM
        for (b in 0 until 4) state[offset + b] = (value ushr (8 * b)).toByte()
    }

    private fun setMain(state: ByteArray, splicer: SavestateSoundSplicer, address: Int, value: Int) {
        val offset = splicer.locate(state).mainRam.offset + address - MAIN_RAM
        for (b in 0 until 4) state[offset + b] = (value ushr (8 * b)).toByte()
    }

    // region formats

    @Test
    fun locatesDesmumeRegions() {
        val state = SavestateSoundSplicer.DESMUME.locate(fixture("ds_cherry_p"))
        assertEquals(0x400000, state.mainRam.size)
        assertEquals(0x10000, state.arm7Wram.size)
        assertEquals(listOf(1084, 0x120), state.soundChip.map { it.size }) // SPU chunk + ARM7 sound registers
        assertEquals(0x021E1AC0, state.arm7(layout.sharedWorkPointer))
    }

    @Test
    fun locatesMelonDsRegions() {
        val state = SavestateSoundSplicer.MELONDS.locate(fixture("mel_p"))
        assertEquals(0x400000, state.mainRam.size)
        assertEquals(state.mainRam.offset + 0x408000, state.arm7Wram.offset)
        assertEquals(listOf(1787), state.soundChip.map { it.size })
        assertEquals(0x021E1AC0, state.arm7(layout.sharedWorkPointer))
    }

    @Test
    fun refusesUnknownFormatsAndVersions() {
        val ds = fixture("ds_cherry_p")
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.MELONDS.locate(ds) }
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(ds.copyOf().also { it[0x10] = 13 }) }
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(ds.copyOf().also { it[0x1C] = 0 }) } // compressed
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(ds.copyOf(1 shl 20)) } // truncated
        val mel = fixture("mel_p")
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(mel) }
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.MELONDS.locate(mel.copyOf().also { it[4] = 10 }) }
    }

    @Test
    fun refusesStatesOfAnotherLayout() {
        val decision = resync(SavestateSoundSplicer.DESMUME).splice(fixture("ds_cherry_p"), fixture("mel_s"))
        assertIs<ResyncRefusal.UnsupportedState>(assertIs<ResyncDecision.Refused>(decision).reason)
    }

    // endregion

    // region decoding

    @Test
    fun decodesTheMusicPlayer() {
        for ((name, splicer, player) in listOf(Triple("ds_cherry_p", SavestateSoundSplicer.DESMUME, 7), Triple("mel_p", SavestateSoundSplicer.MELONDS, 15))) {
            val driver = SoundDriverState.read(splicer.locate(fixture(name)), layout)
            val music = driver.players.single()
            assertEquals(player, music.index, name)
            assertEquals(1, music.flags)
            assertEquals(9, music.tracks.size)
            assertTrue(driver.isSafeFrame, name)
            assertEquals(emptyList(), driver.commandsInFlight)
            assertEquals(0, driver.lockedChannels)
        }
    }

    /** In a battle, the field music stays paused on its own player while the battle music plays. */
    @Test
    fun decodesTheBattleMusicNextToThePausedFieldMusic() {
        for ((name, music, field) in listOf(Triple("ds_battle_will_p", 6, 4), Triple("ds_battle_wild_p", 12, 9))) {
            val driver = SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(fixture(name)), layout)
            assertEquals(listOf(field, music), driver.players.map { it.index }, name)
            assertEquals(music, driver.playing.single().index, name)
            assertEquals(setOf(field), driver.pausedPlayerState.keys, name)
            val paused = driver.players.first { it.index == field }
            assertTrue(paused.isPaused, name)
            assertEquals(0x24 + 0x40 * paused.tracks.size, driver.pausedPlayerState.getValue(field).size, name)
        }
    }

    @Test
    fun seesTheSoundThreadRunning() {
        assertTrue(SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(fixture("ds_cherry_busy")), layout).soundThreadRunning)
        assertTrue(SoundDriverState.read(SavestateSoundSplicer.MELONDS.locate(fixture("mel_busy")), layout).soundThreadRunning)
    }

    // endregion

    // region splice

    @Test
    fun spliceChangesOnlyTheSoundStateAndTakesTheShadows() {
        for ((name, splicer) in listOf("ds_cherry" to SavestateSoundSplicer.DESMUME, "mel" to SavestateSoundSplicer.MELONDS)) {
            val paused = fixture("${name}_p")
            val shadow = fixture("${name}_s")
            val spliced = assertIs<ResyncDecision.Spliced>(resync(splicer).splice(paused, shadow), name).state
            val p = splicer.locate(paused)
            val s = splicer.locate(shadow)
            val allowed = splicer.splicedRegions(p, layout)
            var changed = 0
            for (i in paused.indices) {
                val inAllowed = allowed.any { i >= it.offset && i < it.end }
                if (inAllowed) {
                    assertEquals(shadow[i], spliced[i], "$name: byte $i must come from the shadow")
                } else if (spliced[i] != paused[i]) {
                    error("$name: byte $i changed outside the sound state")
                }
                if (spliced[i] != paused[i]) changed++
            }
            assertTrue(changed > 0, "$name: the shadow's music state differs from the paused one")
            assertContentEquals(paused.copyOfRange(p.mainRam.offset, p.mainRam.end), spliced.copyOfRange(p.mainRam.offset, p.mainRam.end))
            // The sequencer work is the shadow's.
            val work = layout.work - ARM7_WRAM
            assertContentEquals(
                shadow.copyOfRange(s.arm7Wram.offset + work, s.arm7Wram.offset + work + 0x1180),
                spliced.copyOfRange(p.arm7Wram.offset + work, p.arm7Wram.offset + work + 0x1180),
            )
        }
    }

    // endregion

    // region guards

    private fun refusal(splicer: SavestateSoundSplicer, paused: ByteArray, shadow: ByteArray): ResyncRefusal? =
        (resync(splicer).splice(paused, shadow) as? ResyncDecision.Refused)?.reason

    @Test
    fun refusesWhenTheSoundThreadRanAtThePause() {
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_busy"), fixture("ds_cherry_s")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, refusal(SavestateSoundSplicer.MELONDS, fixture("mel_busy"), fixture("mel_s")))
    }

    @Test
    fun refusesAShadowNotAtASafeFrame() {
        assertEquals(ResyncRefusal.ShadowNotAtSafeFrame, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), fixture("ds_cherry_busy")))
        val queued = fixture("ds_cherry_s").also { setArm7(it, SavestateSoundSplicer.DESMUME, layout.commandQueue + 0x1C, 1) }
        assertEquals(ResyncRefusal.ShadowNotAtSafeFrame, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), queued))
        assertTrue(!resync(SavestateSoundSplicer.DESMUME).isSafeFrame(queued))
        assertTrue(resync(SavestateSoundSplicer.DESMUME).isSafeFrame(fixture("ds_cherry_s")))
    }

    /** A second active player at the pause (a sound effect, a cry...) that ended during it: the music is resynced. */
    @Test
    fun acceptsASoundThatEndedDuringThePause() {
        val paused = fixture("ds_cherry_p").also {
            val player0 = layout.work + 0x540
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + player0 - ARM7_WRAM
            it[offset] = (it[offset].toInt() or 1).toByte()
        }
        val decision = assertIs<ResyncDecision.Spliced>(resync(SavestateSoundSplicer.DESMUME).splice(paused, fixture("ds_cherry_s")))
        assertEquals(2, decision.atPause.playing.size)
        val result = resync(SavestateSoundSplicer.DESMUME).apply(FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf()), paused, decision)
        assertEquals(1, assertIs<ResyncResult.Resynced>(result).endedDuringPause)
    }

    /** A second active player at the shadow's end: started during the pause, the resumed game would start it again. */
    @Test
    fun refusesWhenASoundStillPlaysAtTheEnd() {
        val shadow = fixture("ds_cherry_s").also {
            val player0 = layout.work + 0x540
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + player0 - ARM7_WRAM
            it[offset] = (it[offset].toInt() or 1).toByte()
        }
        assertEquals(ResyncRefusal.NotOnlyMusic(1, 2), refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), shadow))
    }

    /**
     * A wild battle's intro, paused while a sound effect and the wild Pokémon's cry play next to the battle music: they
     * end during the pause, the music alone plays at the shadow's end, and the game resumes with it (main RAM
     * unchanged), seeing the two sounds finished.
     */
    @Test
    fun resyncsABattleIntroWhoseCryAndSoundEffectEndedDuringThePause() {
        val splicer = SavestateSoundSplicer.DESMUME
        val paused = fixture("ds_intro_cry_p")
        val atPause = SoundDriverState.read(splicer.locate(paused), layout)
        assertEquals(listOf(1, 2, 11), atPause.playing.map { it.index }) // sound effect, battle music, cry
        assertEquals(listOf(1, 11, 2), atPause.playing.sortedBy { it.tracks.size }.map { it.index })
        assertEquals(setOf(0), atPause.pausedPlayerState.keys) // the field music
        val decision = assertIs<ResyncDecision.Spliced>(resync(splicer).splice(paused, fixture("ds_intro_cry_s")))
        assertEquals(listOf(2), decision.atEnd.playing.map { it.index })
        val main = FakeConsole(splicer, paused.copyOf())
        val result = assertIs<ResyncResult.Resynced>(resync(splicer).apply(main, paused, decision))
        assertEquals(2, result.music.index)
        assertEquals(2, result.endedDuringPause)
        val ram = splicer.locate(paused).mainRam
        assertContentEquals(paused.copyOfRange(ram.offset, ram.end), main.state.copyOfRange(ram.offset, ram.end))
    }

    /**
     * The user's case: paused the frame the battle command menu appears (FIGHT / BAG / POKéMON / RUN), resumed 10 s
     * later. The menu's sound effect (a one-track sequence on its own player) plays at the pause and ends during it,
     * and the battle music crosses its loop point: refused before ("players: 2 at the pause, 1 at the end"), resynced
     * now, at once (no settle frames needed), the main RAM unchanged.
     */
    @Test
    fun resyncsAPauseStartedWhenTheBattleCommandMenuAppears() {
        val splicer = SavestateSoundSplicer.DESMUME
        for ((name, music, sound) in listOf(Triple("ds_menu_wild", 2, 12), Triple("ds_menu_will", 8, 14))) {
            val paused = fixture("${name}_p")
            val shadow = fixture("${name}_s")
            val atPause = SoundDriverState.read(splicer.locate(paused), layout)
            assertEquals(listOf(music, sound), atPause.playing.map { it.index }, name)
            assertEquals(1, atPause.playing.single { it.index == sound }.tracks.size, name)
            assertNull(resync(splicer).pauseRefusal(paused), name)
            assertTrue(!resync(splicer).soundsStartedPlayAtEnd(paused, shadow), name)
            val decision = assertIs<ResyncDecision.Spliced>(resync(splicer).splice(paused, shadow), name)
            assertEquals(listOf(music), decision.atEnd.playing.map { it.index }, name)
            val main = FakeConsole(splicer, paused.copyOf())
            val result = assertIs<ResyncResult.Resynced>(resync(splicer).apply(main, paused, decision), name)
            assertEquals(music, result.music.index, name)
            assertEquals(1, result.endedDuringPause, name)
            val ram = splicer.locate(paused).mainRam
            assertContentEquals(paused.copyOfRange(ram.offset, ram.end), main.state.copyOfRange(ram.offset, ram.end), name)
        }
    }

    /** The same intro, a sound effect started by the shadow's game still playing at its end: refused. */
    @Test
    fun refusesABattleIntroWhoseShadowStartedASound() {
        assertEquals(ResyncRefusal.NotOnlyMusic(1, 2), refusal(SavestateSoundSplicer.DESMUME, fixture("ds_intro_new_p"), fixture("ds_intro_new_s")))
    }

    /**
     * The user's Route 13 case: standing on the sea shore, a map soundplate plays its looping ambient sound (a one-track
     * sound effect) next to the route music for as long as the player stays there, so it plays at the pause and still
     * at the shadow's end, the same. Refused before ("not only the music playing at the end (players: 2 at the pause,
     * 2 at the end)": every pause there jumped back); resynced now, both sounds taken from the shadow, the main RAM
     * unchanged, and no settle frames waited for a sound that never ends.
     */
    @Test
    fun resyncsRoute13WithTheShoreSoundPlayingAllThroughThePause() {
        val splicer = SavestateSoundSplicer.DESMUME
        for ((name, shore) in listOf("ds_r13_shore" to 10, "ds_r13_enter" to 4)) {
            val paused = fixture("${name}_p")
            val shadow = fixture("${name}_s")
            val atPause = SoundDriverState.read(splicer.locate(paused), layout)
            assertEquals(setOf(7, shore), atPause.playing.map { it.index }.toSet(), name)
            assertEquals(1, atPause.playing.single { it.index == shore }.tracks.size, name)
            assertNull(resync(splicer).pauseRefusal(paused), name)
            assertTrue(!resync(splicer).soundsStartedPlayAtEnd(paused, shadow), name)
            val decision = assertIs<ResyncDecision.Spliced>(resync(splicer).splice(paused, shadow), name)
            assertEquals(atPause.playing.toSet(), decision.atEnd.playing.toSet(), name)
            val main = FakeConsole(splicer, paused.copyOf())
            val result = assertIs<ResyncResult.Resynced>(resync(splicer).apply(main, paused, decision), name)
            assertEquals(7, result.music.index, name)
            assertEquals(13, result.music.tracks.size, name)
            assertEquals(listOf(shore), result.playingAlong.map { it.index }, name)
            assertEquals(0, result.endedDuringPause, name)
            val ram = splicer.locate(paused).mainRam
            assertContentEquals(paused.copyOfRange(ram.offset, ram.end), main.state.copyOfRange(ram.offset, ram.end), name)
            // The resumed game finds the shore sound still playing, as the shadow left it.
            assertEquals(decision.atEnd.players, SoundDriverState.read(splicer.locate(main.state), layout).players, name)
        }
    }

    /**
     * Paused mid-step on Route 13's shore: the shadow's game finishes the step onto the next soundplate, stops the
     * ambient sound and starts it again on another player. The resumed game would do that step itself, its own
     * bookkeeping not knowing the shadow's player: refused, and the shadow may play on to see it end (it never does).
     */
    @Test
    fun refusesTheShoreSoundStartedAgainDuringThePause() {
        val splicer = SavestateSoundSplicer.DESMUME
        val paused = fixture("ds_r13_shore_new_p")
        val shadow = fixture("ds_r13_shore_new_s")
        assertEquals(listOf(7, 10), SoundDriverState.read(splicer.locate(paused), layout).playing.map { it.index })
        assertEquals(listOf(7, 13), SoundDriverState.read(splicer.locate(shadow), layout).playing.map { it.index })
        assertEquals(ResyncRefusal.NotOnlyMusic(2, 2), refusal(splicer, paused, shadow))
        assertTrue(resync(splicer).soundsStartedPlayAtEnd(paused, shadow))
    }

    /** A sound playing at the pause and at the end, but changed (another bank): not the same sound, refused. */
    @Test
    fun refusesASecondSoundThatChangedDuringThePause() {
        val splicer = SavestateSoundSplicer.DESMUME
        val shore = layout.work + 0x540 + 10 * 0x24
        val shadow = fixture("ds_r13_shore_s").also { setArm7(it, splicer, shore + 0x20, 0x02123456) }
        assertEquals(ResyncRefusal.NotOnlyMusic(2, 2), refusal(splicer, fixture("ds_r13_shore_p"), shadow))
        // The music alone at the end while the shore sound played at the pause: it ended during the pause, fine.
        val ended = fixture("ds_r13_shore_s").also {
            val offset = splicer.locate(it).arm7Wram.offset + shore - ARM7_WRAM
            it[offset] = (it[offset].toInt() and 1.inv()).toByte()
        }
        val decision = assertIs<ResyncDecision.Spliced>(resync(splicer).splice(fixture("ds_r13_shore_p"), ended))
        assertEquals(listOf(7), decision.atEnd.playing.map { it.index })
    }

    /**
     * The user's Route 22 case: walking from Viridian City onto Route 22, HeartGold fades Viridian's music out for ~2 s
     * (the route-name banner sliding in) before it starts Route 22's. A pause starting then and lasting past the change
     * sees the shadow's game start the new song, its data loaded into the sound heap (main RAM, never copied): refused,
     * the music jumping back to Viridian's fade at resume. The paused state tells the change is coming (the game's sound
     * state machine and countdowns), even on the frame between stopping the old song and starting the new one; the
     * pause put off until the new song plays (as the app does) is resynced.
     */
    @Test
    fun aPauseDuringTheSongChangeOfRoute22IsRefusedAndOneStartingOnTheNewSongIsResynced() {
        val splicer = SavestateSoundSplicer.DESMUME
        val ds = resync(splicer)
        for (name in listOf("ds_r22_fade", "ds_r22_load")) {
            val paused = fixture("${name}_p")
            assertEquals(GameMusicPhase.SONG_QUEUED, SoundDriverState.read(splicer.locate(paused), layout).gameMusic, name)
            assertTrue(ds.songChangePending(paused), name)
            assertNull(ds.pauseRefusal(paused), name) // nothing a frame or two later would fix
            assertEquals(ResyncRefusal.SongChanged, refusal(splicer, paused, fixture("${name}_s")), name)
        }
        assertEquals(11, SoundDriverState.read(splicer.locate(fixture("ds_r22_fade_p")), layout).playing.single().tracks.size)
        // The frame between the two songs: Viridian's already stopped, Route 22's not started yet.
        assertTrue(SoundDriverState.read(splicer.locate(fixture("ds_r22_load_p")), layout).playing.none { it.tracks.size > 1 })

        val paused = fixture("ds_r22_new_p")
        assertEquals(GameMusicPhase.PLAYING, SoundDriverState.read(splicer.locate(paused), layout).gameMusic)
        assertTrue(!ds.songChangePending(paused))
        assertTrue(!ds.songChangePending(paused, afterFadeOut = true))
        val decision = assertIs<ResyncDecision.Spliced>(ds.splice(paused, fixture("ds_r22_new_s")))
        assertEquals(11, decision.atEnd.playing.single().tracks.size)
        val main = FakeConsole(splicer, paused.copyOf())
        assertIs<ResyncResult.Resynced>(ds.apply(main, paused, decision))
        val ram = splicer.locate(paused).mainRam
        assertContentEquals(paused.copyOfRange(ram.offset, ram.end), main.state.copyOfRange(ram.offset, ram.end))
    }

    /**
     * Out of the Viridian / Route 1 gatehouse (a warp): the player stands on Route 1 while the gatehouse's music still
     * fades out (~1.5 s), then no music for a few frames, then Route 1's. A pause in the fade-out is a change coming;
     * in the gap, only for a caller that saw the fade-out (a stopped music is otherwise a silent scene, not a change).
     */
    @Test
    fun aPauseOutOfAGatehouseWaitsThroughTheFadeOutAndTheGap() {
        val splicer = SavestateSoundSplicer.DESMUME
        val ds = resync(splicer)
        val fading = fixture("ds_r1_gate_fade_p")
        assertEquals(GameMusicPhase.FADING_OUT, SoundDriverState.read(splicer.locate(fading), layout).gameMusic)
        assertTrue(ds.songChangePending(fading))
        assertEquals(ResyncRefusal.SongChanged, refusal(splicer, fading, fixture("ds_r1_gate_fade_s")))
        val gap = fixture("ds_r1_gate_gap_p")
        assertEquals(GameMusicPhase.STOPPED, SoundDriverState.read(splicer.locate(gap), layout).gameMusic)
        assertTrue(SoundDriverState.read(splicer.locate(gap), layout).playing.isEmpty())
        assertTrue(!ds.songChangePending(gap))
        assertTrue(ds.songChangePending(gap, afterFadeOut = true))
        assertEquals(ResyncRefusal.SongChanged, refusal(splicer, gap, fixture("ds_r1_gate_gap_s")))
    }

    /**
     * The game's music state machine, word by word ([GameMusicPhase]): a queued song (state 5 / 6) with a countdown
     * running, or once they ended with a song queued; left at 5 with no song queued, the music only kept stopped; a
     * fade-out (4) while its countdown runs; a song starting (1); stopped (0); playing or fading in (2, 3). A layout that
     * doesn't know the game's music logic never tells a change.
     */
    @Test
    fun tellsTheGamesMusicPhaseFromItsStateMachine() {
        val splicer = SavestateSoundSplicer.DESMUME
        val music = assertNotNull(layout.gameMusic)
        fun phase(vararg words: Pair<Int, Int>, layout: SoundDriverLayout = this.layout): GameMusicPhase? {
            val state = fixture("ds_r22_load_p")
            for ((address, value) in words) setMain(state, splicer, address, value)
            return SoundDriverState.read(splicer.locate(state), layout).gameMusic
        }
        assertEquals(GameMusicPhase.SONG_QUEUED, phase()) // state 5, countdowns over, Route 22's song queued
        assertEquals(GameMusicPhase.STOPPED, phase(music.queuedSong to 0)) // nothing queued: the music stays stopped
        assertEquals(GameMusicPhase.SONG_QUEUED, phase(music.queuedSong to 0, music.fadeTimer to 12)) // fading out
        assertEquals(GameMusicPhase.SONG_QUEUED, phase(music.queuedSong to 0, music.afterFadeTimer to 3)) // the delay after it
        assertEquals(GameMusicPhase.SONG_QUEUED, phase(music.state to 6)) // the bicycle's change, with a fade-in
        assertEquals(GameMusicPhase.FADING_OUT, phase(music.state to 4, music.fadeTimer to 12))
        assertEquals(GameMusicPhase.PLAYING, phase(music.state to 4)) // fade-out over (the state machine moves on)
        assertEquals(GameMusicPhase.STARTING, phase(music.state to 1))
        assertEquals(GameMusicPhase.STOPPED, phase(music.state to 0))
        assertEquals(GameMusicPhase.PLAYING, phase(music.state to 2))
        assertEquals(GameMusicPhase.PLAYING, phase(music.state to 3, music.fadeTimer to 12)) // fading in
        assertNull(phase(layout = layout.copy(gameMusic = null)))
        assertTrue(!SoundResync(layout.copy(gameMusic = null), splicer).songChangePending(fixture("ds_r22_load_p")))
        // Fixtures captured before the game's music words were kept read zeros (stopped): no change for a caller that
        // didn't see one coming.
        assertTrue(!resync(splicer).songChangePending(fixture("ds_r13_enter_p")))
        assertTrue(!resync(SavestateSoundSplicer.MELONDS).songChangePending(fixture("mel_p")))
    }

    /** Battles: the paused field music doesn't count as a second sound, and its state stays the paused game's. */
    @Test
    fun resyncsABattleWhoseFieldMusicIsPaused() {
        for (name in listOf("ds_battle_will", "ds_battle_wild")) {
            val decision = assertIs<ResyncDecision.Spliced>(resync(SavestateSoundSplicer.DESMUME).splice(fixture("${name}_p"), fixture("${name}_s")), name)
            assertEquals(decision.atPause.pausedPlayerState, SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(decision.state), layout).pausedPlayerState)
            val main = FakeConsole(SavestateSoundSplicer.DESMUME, fixture("${name}_p"))
            val result = resync(SavestateSoundSplicer.DESMUME).apply(main, fixture("${name}_p"), decision)
            assertEquals(decision.atPause.playing.single(), assertIs<ResyncResult.Resynced>(result, name).music)
        }
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_battle_will_busy"), fixture("ds_battle_will_s")))
    }

    /** A paused player that moved during the pause (the game touched it): not the music's state to take. */
    @Test
    fun refusesWhenAPausedPlayerMoved() {
        val shadow = fixture("ds_battle_will_s").also {
            val field = layout.work + 0x540 + 4 * 0x24
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + field + 0x1C - ARM7_WRAM
            it[offset]++
        }
        assertEquals(ResyncRefusal.PausedPlayerMoved, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_battle_will_p"), shadow))
        // Unpaused during the pause: two sequences playing at the end.
        val unpaused = fixture("ds_battle_will_s").also {
            val field = layout.work + 0x540 + 4 * 0x24
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + field - ARM7_WRAM
            it[offset] = (it[offset].toInt() and SoundDriverState.PAUSED.inv()).toByte()
        }
        assertEquals(ResyncRefusal.NotOnlyMusic(1, 2), refusal(SavestateSoundSplicer.DESMUME, fixture("ds_battle_will_p"), unpaused))
    }

    @Test
    fun refusesWhenTheSongChanged() {
        val shadow = fixture("ds_cherry_s").also {
            val player7 = layout.work + 0x540 + 7 * 0x24
            setArm7(it, SavestateSoundSplicer.DESMUME, player7 + 0x20, 0x02123456) // another bank
        }
        assertEquals(ResyncRefusal.SongChanged, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), shadow))
    }

    @Test
    fun refusesLockedChannels() {
        val shadow = fixture("mel_s").also { setArm7(it, SavestateSoundSplicer.MELONDS, layout.lockedChannels.first(), 0x8000) }
        assertEquals(ResyncRefusal.LockedChannels(0x8000), refusal(SavestateSoundSplicer.MELONDS, fixture("mel_p"), shadow))
    }

    /** One more list flushed by the ARM9 than the ARM7 finished, holding a single command [id]. */
    private fun withCommandInFlight(id: Int): ByteArray = fixture("ds_cherry_p").also { state ->
        val splicer = SavestateSoundSplicer.DESMUME
        val located = splicer.locate(state)
        val shared = located.arm7(layout.sharedWorkPointer)
        val arm9 = layout.arm9Commands
        val write = located.main(shared + arm9.waitingWrite)
        val command = 0x021E1D40 // sCommandArray[0]
        setMain(state, splicer, shared + arm9.currentTag, located.main(shared + arm9.currentTag) + 1)
        setMain(state, splicer, shared + arm9.waitingQueue + ((write - 1 + arm9.waitingSlots) % arm9.waitingSlots) * 4, command)
        setMain(state, splicer, command, 0) // last command of the list
        setMain(state, splicer, command + 4, id)
    }

    @Test
    fun acceptsIdempotentCommandsInFlightAtThePause() {
        val paused = withCommandInFlight(0x21) // READ_DRIVER_INFO
        assertEquals(listOf(0x21), SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(paused), layout).commandsInFlight)
        assertIs<ResyncDecision.Spliced>(resync(SavestateSoundSplicer.DESMUME).splice(paused, fixture("ds_cherry_s")))
    }

    /** A state taken while `SND_FlushCommand` runs: the ARM7 already finished the list the ARM9 hasn't counted yet. */
    @Test
    fun aListFinishedBeforeItsFlushIsCountedIsNotInFlight() {
        val splicer = SavestateSoundSplicer.DESMUME
        val state = fixture("ds_cherry_p").also {
            val located = splicer.locate(it)
            val shared = located.arm7(layout.sharedWorkPointer)
            setMain(it, splicer, shared, located.main(shared + layout.arm9Commands.currentTag)) // finished = current tag
        }
        assertEquals(emptyList(), SoundDriverState.read(splicer.locate(state), layout).commandsInFlight)
        assertNull(resync(splicer).pauseRefusal(state))
    }

    /** Inconsistent bookkeeping (more lists in flight than the queue holds): refused, the values told for the logs. */
    @Test
    fun tellsWhyTheCommandsInFlightCantBeRead() {
        val splicer = SavestateSoundSplicer.DESMUME
        val state = fixture("ds_cherry_p").also {
            val located = splicer.locate(it)
            val shared = located.arm7(layout.sharedWorkPointer)
            setMain(it, splicer, shared + layout.arm9Commands.currentTag, located.main(shared) + 20)
        }
        val refusal = assertIs<ResyncRefusal.UnreadableCommands>(resync(splicer).pauseRefusal(state))
        assertTrue("finished tag" in refusal.message, refusal.message)
    }

    @Test
    fun refusesCommandsInFlightThatCantRunTwice() {
        assertEquals(ResyncRefusal.CommandsInFlightAtPause(listOf(0x00)), refusal(SavestateSoundSplicer.DESMUME, withCommandInFlight(0x00), fixture("ds_cherry_s")))
    }

    /** The paused-state guards alone: what the app checks to start a pause one frame later (see `ShadowAudio`). */
    @Test
    fun tellsFramesAPauseCantStartFrom() {
        val ds = resync(SavestateSoundSplicer.DESMUME)
        assertNull(ds.pauseRefusal(fixture("ds_cherry_p")))
        assertNull(ds.pauseRefusal(fixture("ds_battle_will_p")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, ds.pauseRefusal(fixture("ds_cherry_busy")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, ds.pauseRefusal(fixture("ds_battle_will_busy")))
        assertEquals(ResyncRefusal.CommandsInFlightAtPause(listOf(0x00)), ds.pauseRefusal(withCommandInFlight(0x00)))
        assertNull(ds.pauseRefusal(withCommandInFlight(0x21)))
        assertIs<ResyncRefusal.UnsupportedState>(ds.pauseRefusal(fixture("mel_p")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, resync(SavestateSoundSplicer.MELONDS).pauseRefusal(fixture("mel_busy")))
    }

    // endregion

    // region main RAM identity

    /** A console whose whole state is a save state blob, its main RAM being the blob's main RAM region. */
    private class FakeConsole(private val splicer: SavestateSoundSplicer, var state: ByteArray, val afterLoad: (ByteArray) -> Unit = {}) : ConsolePort {
        var loads = mutableListOf<ByteArray>()
        var rejects = false
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override var revision = 0L
        override fun step(frames: Int, input: InputFrame) { frame += frames }
        override fun memorySize(region: MemoryRegion) = 0x400000
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) {
            val ram = splicer.locate(state).mainRam
            state.copyInto(into, 0, ram.offset + offset, ram.offset + offset + length)
        }
        override fun framebuffer(): Frame? = null
        override fun saveState() = state.copyOf()
        override fun loadState(state: ByteArray): Boolean {
            if (rejects && loads.isEmpty()) return false.also { loads += state }
            loads += state
            this.state = state.copyOf().also(afterLoad)
            revision++
            return true
        }
    }

    private fun spliced(splicer: SavestateSoundSplicer, name: String) =
        assertIs<ResyncDecision.Spliced>(resync(splicer).splice(fixture("${name}_p"), fixture("${name}_s")))

    @Test
    fun appliesTheSpliceWithTheMainRamUnchanged() {
        val paused = fixture("ds_cherry_p")
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf())
        val decision = spliced(SavestateSoundSplicer.DESMUME, "ds_cherry")
        assertIs<ResyncResult.Resynced>(resync(SavestateSoundSplicer.DESMUME).apply(main, paused, decision))
        assertContentEquals(decision.state, main.state)
    }

    @Test
    fun restoresThePausedStateWhenTheMainRamDiffersAfterLoading() {
        val paused = fixture("mel_p")
        val ram = SavestateSoundSplicer.MELONDS.locate(paused).mainRam
        var corrupt = true
        val main = FakeConsole(SavestateSoundSplicer.MELONDS, paused.copyOf()) { loaded -> if (corrupt) loaded[ram.offset + 1234]++; corrupt = false }
        val result = resync(SavestateSoundSplicer.MELONDS).apply(main, paused, spliced(SavestateSoundSplicer.MELONDS, "mel"))
        assertIs<ResyncResult.Aborted>(result)
        assertContentEquals(paused, main.state) // the paused state, loaded back
    }

    @Test
    fun refusesWhenTheMainRamChangedDuringThePause() {
        val paused = fixture("ds_cherry_p")
        val ram = SavestateSoundSplicer.DESMUME.locate(paused).mainRam
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf().also { it[ram.offset + 99]++ })
        val result = resync(SavestateSoundSplicer.DESMUME).apply(main, paused, spliced(SavestateSoundSplicer.DESMUME, "ds_cherry"))
        assertEquals(ResyncResult.Refused(ResyncRefusal.MainRamChangedDuringPause), result)
        assertTrue(main.loads.isEmpty(), "nothing loaded")
    }

    @Test
    fun loadsThePausedStateBackWhenTheCoreRejectsTheSplice() {
        val paused = fixture("ds_cherry_p")
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf()).also { it.rejects = true }
        assertIs<ResyncResult.Aborted>(resync(SavestateSoundSplicer.DESMUME).apply(main, paused, spliced(SavestateSoundSplicer.DESMUME, "ds_cherry")))
        assertContentEquals(paused, main.loads.last())
    }

    // endregion

    // region shadow

    /** A shadow whose frames walk through [states] (what [ConsolePort.saveState] returns after each step). */
    private class FakeShadow(private val states: List<ByteArray>) : ConsolePort {
        val loaded = mutableListOf<ByteArray>()
        val steps = mutableListOf<Boolean>() // silenced?
        var silenced = false
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override var revision = 0L
        override fun step(frames: Int, input: InputFrame) { repeat(frames) { steps += silenced; frame++ } }
        override fun memorySize(region: MemoryRegion) = 0
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = states[minOf(frame.toInt(), states.size - 1)]
        override fun loadState(state: ByteArray): Boolean { loaded += state; frame = 0; return true }
    }

    @Test
    fun shadowEndsAtTheFirstSafeFrame() {
        val busy = fixture("ds_cherry_busy")
        val safe = fixture("ds_cherry_s")
        val shadow = FakeShadow(listOf(busy, busy, busy, safe))
        val run = ShadowRun(shadow, resync(SavestateSoundSplicer.DESMUME))
        run.begin(fixture("ds_cherry_p"))
        run.step()
        val end = assertIs<ShadowEnd.Safe>(run.end())
        assertEquals(3, end.frames)
        assertContentEquals(safe, end.state)
    }

    @Test
    fun shadowGivesUpAfterThreeUnsafeFrames() {
        val busy = fixture("ds_cherry_busy")
        val shadow = FakeShadow(listOf(busy))
        val run = ShadowRun(shadow, resync(SavestateSoundSplicer.DESMUME))
        run.begin(fixture("ds_cherry_p"))
        run.step()
        assertEquals(ShadowEnd.NotSafe(1 + ShadowRun.MAX_EXTRA_FRAMES), run.end())
    }

    /**
     * A sound effect the shadow's game started still plays at its first safe frame: with settle frames, the shadow plays
     * on until it ended (then the resync passes), or gives the last safe frame after that many frames.
     */
    @Test
    fun shadowPlaysOnUntilASoundItStartedEnded() {
        val splicer = SavestateSoundSplicer.DESMUME
        val paused = fixture("ds_intro_new_p")
        val withSound = fixture("ds_intro_new_s")
        val soundPlayer = SoundDriverState.read(splicer.locate(withSound), layout).playing.single { it.tracks.size == 1 }
        val ended = withSound.copyOf().also {
            val offset = splicer.locate(it).arm7Wram.offset + layout.work + 0x540 + soundPlayer.index * 0x24 - ARM7_WRAM
            it[offset] = (it[offset].toInt() and 1.inv()).toByte()
        }
        val ds = resync(splicer)
        assertTrue(ds.soundsStartedPlayAtEnd(paused, withSound))
        assertTrue(!ds.soundsStartedPlayAtEnd(paused, ended))
        fun endWith(settle: Int): ShadowEnd.Safe {
            val run = ShadowRun(FakeShadow(listOf(withSound, withSound, withSound, ended)), ds)
            run.begin(paused)
            run.step()
            return assertIs<ShadowEnd.Safe>(run.end(settleFrames = settle))
        }
        assertContentEquals(withSound, endWith(0).state) // at once, as before
        val settled = endWith(ShadowRun.SETTLE_FRAMES)
        assertEquals(3, settled.frames)
        assertContentEquals(ended, settled.state)
        assertIs<ResyncDecision.Spliced>(ds.splice(paused, settled.state))
        val gaveUp = endWith(1)
        assertEquals(2, gaveUp.frames)
        assertContentEquals(withSound, gaveUp.state)
    }

    @Test
    fun aShadowThatPlayedNothingHasNothingToResync() {
        val run = ShadowRun(FakeShadow(listOf(fixture("ds_cherry_s"))), resync(SavestateSoundSplicer.DESMUME))
        run.begin(fixture("ds_cherry_p"))
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"))
        assertEquals(ResyncResult.Refused(ResyncRefusal.NothingPlayed), resync(SavestateSoundSplicer.DESMUME).resume(main, fixture("ds_cherry_p"), run.end()))
        assertTrue(main.loads.isEmpty())
    }

    /** melonDS 0.9.3 crashes loading a mid-burst DMA into a fresh instance: the shadow first runs a silent priming frame. */
    @Test
    fun primesAMelonDsShadowSilentlyBeforeAMidBurstDma() {
        val dma = fixture("mel_dma")
        val priming = assertNotNull(SavestateSoundSplicer.MELONDS.primingState(dma))
        assertEquals(dma.size, priming.size)
        assertEquals(1, dma.indices.count { dma[it] != priming[it] })
        assertNull(SavestateSoundSplicer.MELONDS.primingState(fixture("mel_p")))
        assertNull(SavestateSoundSplicer.DESMUME.primingState(fixture("ds_cherry_p")))

        val shadow = FakeShadow(listOf(fixture("mel_s")))
        val run = ShadowRun(shadow, resync(SavestateSoundSplicer.MELONDS), silence = { shadow.silenced = it })
        assertTrue(run.begin(dma))
        assertEquals(listOf(true), shadow.steps) // one silent frame
        assertContentEquals(priming, shadow.loaded[0])
        assertContentEquals(dma, shadow.loaded[1])
        assertEquals(0, run.frames)
    }

    // endregion

    // region layout

    @Test
    fun recognizesHeartGoldUsByItsArm7Binary() {
        val rom = environmentVariable("POKEMON_ROM")?.let(::Path)?.takeIf(Files::exists) ?: return
        assertEquals(SoundDriverLayout.HEARTGOLD_US, SoundDriverLayout.forRom(rom))
        assertNotNull(SoundDriverLayout.forRom(rom)?.gameMusic)
    }

    /**
     * Another game built with the same ARM7 binary (SoulSilver shares HeartGold's) gets the driver layout, but not
     * HeartGold's music state machine: its addresses are those of HeartGold's ARM9 binary.
     */
    @Test
    fun keepsTheGamesMusicLogicForThatGameOnly() {
        val real = environmentVariable("POKEMON_ROM")?.let(::Path)?.takeIf(Files::exists) ?: return
        val header = Files.readRange(real, 0, 0x200)
        val arm7 = Files.readRange(real, SoundDriverLayout.le32(header, 0x30).toLong(), SoundDriverLayout.le32(header, 0x3C))
        val directory = Files.createTemporaryDirectory("rom")
        try {
            fun romWith(gameCode: String): Path = Path(directory, "$gameCode.nds").also { rom ->
                val bytes = header.copyOf(0x200 + arm7.size)
                gameCode.encodeToByteArray().copyInto(bytes, 0x0C)
                for (b in 0 until 4) bytes[0x30 + b] = (0x200 ushr (8 * b)).toByte() // the ARM7 binary right after the header
                arm7.copyInto(bytes, 0x200)
                Files.writeBytes(rom, bytes)
            }
            assertEquals(SoundDriverLayout.HEARTGOLD_US, SoundDriverLayout.forRom(romWith("IPKE")))
            val soulSilver = assertNotNull(SoundDriverLayout.forRom(romWith("IPGE")))
            assertNull(soulSilver.gameMusic)
            assertEquals(SoundDriverLayout.HEARTGOLD_US.copy(gameMusic = null), soulSilver)
        } finally {
            Files.deleteRecursively(directory)
        }
    }

    @Test
    fun anUnknownArm7BinaryIsNotSupported() {
        val directory = Files.createTemporaryDirectory("rom")
        val rom = Path(directory, "rom.nds")
        try {
            val bytes = ByteArray(0x1000)
            bytes[0x30] = 0x00; bytes[0x31] = 0x02 // ARM7 at 0x200
            bytes[0x3C] = 0x00; bytes[0x3D] = 0x01 // 0x100 bytes
            Files.writeBytes(rom, bytes)
            assertNotNull(SoundDriverLayout.arm7Sha1(rom))
            assertNull(SoundDriverLayout.forRom(rom))
        } finally {
            Files.deleteRecursively(directory)
        }
    }

    // endregion
}
