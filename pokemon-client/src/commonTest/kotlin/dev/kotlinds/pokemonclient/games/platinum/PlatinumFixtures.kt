package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.SparseFixtures
import dev.kotlinds.pokemonclient.assumeTrue
import dev.kotlinds.pokemonclient.environmentVariable
import dev.kotlinds.pokemonclient.readFileOrNull

/** Sparse RAM fixtures captured on Platinum (USA) with the bench (`fixture:pt_<name>`), in resources/platinum/. */
object PlatinumFixtures {
    fun load(name: String): Memory = SparseFixtures.load("platinum", name)
}

/**
 * The Platinum ROM of `PLATINUM_ROM` (Pokémon Platinum USA, game code CPUE), parsed once; tests needing it are skipped
 * without it (like the HGSS ROM tests with `POKEMON_ROM`).
 */
object PlatinumRom {
    private val rom: NdsRom? by lazy {
        val path = environmentVariable("PLATINUM_ROM") ?: return@lazy null
        NdsRom.parse(readFileOrNull(path) ?: return@lazy null).takeIf { PlatinumVersion.forGameCode(it.gameCode) != null }
    }

    private val game: PlatinumGame? by lazy { rom?.let { PlatinumGame(PlatinumVersion.PLATINUM_US, it) } }

    fun requireRom(): NdsRom {
        assumeTrue(rom != null, "PLATINUM_ROM is not set to a Platinum (USA) ROM: test skipped")
        return rom!!
    }

    /** The game with its ROM (maps, text banks); skips the calling test without `PLATINUM_ROM`. */
    fun requireGame(): PlatinumGame {
        requireRom()
        return game!!
    }
}
