package dev.kotlinds.pokemonclient.libretro.sound

/**
 * Where the game's own music logic is (its ARM9 code above the SDK sound driver, see
 * [SoundDriverLayout.GameMusicLayout]): whether it is changing its song.
 *
 * HeartGold changes the field music in a few steps (pret/pokeheartgold `field_bgm.c`, `sound.c` `GF_SndCallback`),
 * ticked by its 30 Hz field loop:
 * - walking across a map edge into a map with another song (Viridian City → Route 22, `FieldBGM_TryFadeOut`): the
 *   current song fades out (30 or 60 ticks), a few ticks more may pass, then the game stops it and starts the queued
 *   one (`PlayBGM`, loading its sequence from the card, possibly over a frame boundary): [SONG_QUEUED] then
 *   [STARTING], ~2 s from the step into the new map (the route-name banner sliding in) to the new song;
 * - through a warp (a gatehouse door, `FieldBGM_TryFadeIn`): the song fades out ([FADING_OUT], 40 ticks, lasting
 *   after the player stands in the new map), is stopped ([STOPPED], a few frames), and the new map's song is started
 *   ([STARTING]).
 *
 * A pause starting in any of them and lasting past the new song's start can't be resynced (see
 * [SoundResync.songChangePending]).
 */
enum class GameMusicPhase {
    /** A song plays (or fades in): nothing changes. */
    PLAYING,

    /** The song fades out: at the end, the game stops it, and usually starts another (a warp). */
    FADING_OUT,

    /** Another song is queued: it starts once the current one faded out (and a few ticks more). */
    SONG_QUEUED,

    /** A song was just started: it plays from the game's next tick. */
    STARTING,

    /** No song: after a fade-out, until the game starts the next one (or for good, in a silent scene). */
    STOPPED;

    companion object {
        /** The phase of the game's music state machine at [state], read with [music]. */
        internal fun read(state: SoundSavestate, music: SoundDriverLayout.GameMusicLayout): GameMusicPhase? {
            if (!music.addresses.all(state::inMainRam)) return null
            val machine = state.main(music.state)
            val fading = (state.main(music.fadeTimer) and 0xFFFF) != 0
            val counting = fading || (state.main(music.afterFadeTimer) and 0xFFFF) != 0
            return when (machine) {
                music.stoppedState -> STOPPED
                music.startingState -> STARTING
                in music.fadingOutStates -> if (fading) FADING_OUT else PLAYING
                // Countdowns over with a song queued: between stopping the old song and starting the queued one. Left
                // there with no song queued, the state machine only keeps the music stopped.
                in music.songQueuedStates -> if (counting || (state.main(music.queuedSong) and 0xFFFF) != 0) SONG_QUEUED else STOPPED
                else -> PLAYING
            }
        }
    }
}
