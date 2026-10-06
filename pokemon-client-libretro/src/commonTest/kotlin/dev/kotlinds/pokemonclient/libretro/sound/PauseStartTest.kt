package dev.kotlinds.pokemonclient.libretro.sound

import dev.kotlinds.pokemonclient.libretro.readTestResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a pause starts ([PauseStart], the rule shared by the app's `ShadowAudio` and the bench), on the HeartGold US
 * fixtures of [SoundResyncTest]: a frame where the sound thread runs (`ds_cherry_busy`), Route 22's song change
 * (`ds_r22_fade`) and the gatehouse's fade-out then gap before Route 1's song (`ds_r1_gate_fade` / `_gap`).
 */
class PauseStartTest {

    private val resync = SoundResync(SoundDriverLayout.HEARTGOLD_US, SavestateSoundSplicer.DESMUME)

    private fun fixture(name: String): ByteArray =
        SoundFixtures.decode(readTestResource("sound/pausemusic_$name.state.gz"))

    @Test
    fun aPauseOnAFrameThatCanBeResyncedStartsAtOnce() {
        val start = PauseStart(resync)
        assertNull(start.putOff(fixture("ds_cherry_p")))
        assertEquals(0, start.pauseDelay)
        assertEquals(0, start.songChangeWait)
    }

    @Test
    fun aFrameThatCantBeResyncedPutsThePauseOffUpToTheLimit() {
        val busy = fixture("ds_cherry_busy")
        val start = PauseStart(resync)
        repeat(PauseStart.MAX_PAUSE_DELAY_FRAMES) { assertEquals(PauseStart.Reason.NOT_RESYNCABLE, start.putOff(busy)) }
        assertNull(start.putOff(busy), "past the limit, the pause starts anyway")
        assertEquals(PauseStart.MAX_PAUSE_DELAY_FRAMES, start.pauseDelay)
        start.reset() // the game ran on: another pause
        assertEquals(PauseStart.Reason.NOT_RESYNCABLE, start.putOff(busy))
        assertNull(PauseStart(resync, maxPauseDelay = 0).putOff(busy), "no delay asked (the bench's song-wait-only check)")
    }

    @Test
    fun aSongChangePutsThePauseOffUnlessTheSettingIsOff() {
        val fading = fixture("ds_r22_fade_p")
        val start = PauseStart(resync, maxSongChangeWait = 2)
        assertNull(start.putOff(fading, waitForSongChange = false)) // nothing else would put it off (see SoundResyncTest)
        assertEquals(PauseStart.Reason.SONG_CHANGE, start.putOff(fading))
        assertTrue(!start.songChangeWaitExhausted)
        assertEquals(PauseStart.Reason.SONG_CHANGE, start.putOff(fading))
        assertTrue(start.songChangeWaitExhausted, "the change didn't come: the app logs it")
        assertNull(start.putOff(fading), "past the limit, the pause starts anyway")
        assertEquals(2, start.songChangeWait)
        assertEquals(0, start.pauseDelay)
    }

    @Test
    fun theGapAfterAFadeOutIsAChangeOnlyForAPauseThatSawTheFadeOut() {
        val gap = fixture("ds_r1_gate_gap_p")
        assertNotEquals(PauseStart.Reason.SONG_CHANGE, PauseStart(resync).putOff(gap), "a stopped music is a silent scene")
        val start = PauseStart(resync)
        assertEquals(PauseStart.Reason.SONG_CHANGE, start.putOff(fixture("ds_r1_gate_fade_p")))
        assertEquals(PauseStart.Reason.SONG_CHANGE, start.putOff(gap))
    }
}
