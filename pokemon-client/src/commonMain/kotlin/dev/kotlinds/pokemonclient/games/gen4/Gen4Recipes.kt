package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.actions.Recipes

/**
 * The recipes of the Generation 4 games ([Gen4Game.recipes]): what their shared engine carries out the same way in
 * every one of them, where it differs from the common recipes. Nothing differs yet: the common recipes are the Gen 4
 * ones (HeartGold / SoulSilver's); what only Gen 4 does moves here when another generation needs it to differ.
 *
 * Abstract: each Gen 4 game gives its own subclass ([dev.kotlinds.pokemonclient.games.hgss.HgssRecipes],
 * [dev.kotlinds.pokemonclient.games.platinum.PlatinumRecipes]), even empty, so a game's own recipe always has its
 * place. Stateless, like every [Recipes].
 */
abstract class Gen4Recipes internal constructor() : Recipes()
