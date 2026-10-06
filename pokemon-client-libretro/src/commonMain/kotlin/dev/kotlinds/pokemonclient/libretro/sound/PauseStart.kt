package dev.kotlinds.pokemonclient.libretro.sound

/**
 * Music during pauses, when a pause starts: the one rule deciding whether a pause asked at a frame had better start a
 * frame later, the game then emulating one more frame through its normal emulation, as if the pause had been asked
 * then. Used by the app (`ShadowAudio`, as the game is about to freeze) and by the bench's `pausemusic` commands, so
 * what the bench measures is what the app does.
 *
 * One instance follows one coming pause: [putOff] is asked once per frame until it returns null (the pause starts
 * there), counting the frames already put off; [reset] when the game runs on (a pause coming later is another one).
 *
 * A pause is put off, in this order:
 * - while the game is about to change its song ([SoundResync.songChangePending]: fading the old one out, ~2 s from
 *   the step into a route with its own music), up to [maxSongChangeWait] frames: a pause lasting past the change
 *   can't be resynced (the new song's data is loaded during the pause), one starting on the new song can. The game
 *   runs on meanwhile (sound heard), so what it shows may move on a little after the pause was asked;
 * - then, because a pause starting at this frame couldn't be resynced ([SoundResync.pauseRefusal]: the ARM7 is in the
 *   middle of a sequencer tick, or of sound commands that can't run twice; ~12% of frames, measured). That lasts a
 *   frame: at most [maxPauseDelay], the pause starting ~17 ms later.
 *
 * Single-threaded: the thread that drives the main console.
 */
class PauseStart(
    private val resync: SoundResync,
    /** At most this many frames put off for a song change (0: never, the app's "Wait for the song change" off). */
    val maxSongChangeWait: Int = MAX_SONG_CHANGE_WAIT_FRAMES,
    /** At most this many frames put off for a frame a pause can't be resynced from (0: never). */
    val maxPauseDelay: Int = MAX_PAUSE_DELAY_FRAMES,
) {

    /** Frames the coming pause was already put off for a song change. */
    var songChangeWait = 0
        private set

    /** Frames the coming pause was already put off for frames a pause can't be resynced from. */
    var pauseDelay = 0
        private set

    /** Why a pause is put off by a frame. */
    enum class Reason {
        /** The game is changing its song ([SoundResync.songChangePending]). */
        SONG_CHANGE,

        /** A pause starting at this frame couldn't be resynced ([SoundResync.pauseRefusal]). */
        NOT_RESYNCABLE,
    }

    /**
     * Whether the pause asked at [state] (the main console's state at this frame) had better start one frame later,
     * and why; null when it starts here. [waitForSongChange] false skips the song-change wait (the app's setting).
     * Each non-null answer counts one frame put off.
     */
    fun putOff(state: ByteArray, waitForSongChange: Boolean = true): Reason? = when {
        waitForSongChange && songChangeWait < maxSongChangeWait &&
            resync.songChangePending(state, afterFadeOut = songChangeWait > 0) -> Reason.SONG_CHANGE.also { songChangeWait++ }
        pauseDelay < maxPauseDelay && resync.pauseRefusal(state) != null -> Reason.NOT_RESYNCABLE.also { pauseDelay++ }
        else -> null
    }

    /** Whether the song-change wait reached [maxSongChangeWait] (the change didn't come: the pause starts anyway). */
    val songChangeWaitExhausted: Boolean get() = maxSongChangeWait > 0 && songChangeWait == maxSongChangeWait

    /** The coming pause wasn't put off yet: the game runs on, a pause coming later is another one. */
    fun reset() {
        songChangeWait = 0
        pauseDelay = 0
    }

    companion object {
        /** A frame where a pause can start comes within 1 frame (measured over 200 pauses); 3 at most. */
        const val MAX_PAUSE_DELAY_FRAMES = 3

        /**
         * A song change the game has queued comes within ~2.5 s: the field fades the old song out over 30 to 60 ticks
         * of its 30 Hz loop, then waits 0 to 15 more (HeartGold `FieldBGM_GetFadeOutAndWaitFrames`); measured from
         * Viridian City onto Route 22: 121 frames from the step into the route. Past this (a fanfare holds the
         * countdown), the pause starts anyway.
         */
        const val MAX_SONG_CHANGE_WAIT_FRAMES = 180
    }
}
