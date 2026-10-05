package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.SparseFixtures

/** Real HeartGold (USA) RAM snapshots (sparse fixtures in resources/hgss/, see [SparseFixtures]). */
object HgssFixtures {
    fun load(name: String): Memory = SparseFixtures.load("hgss", name)
}
