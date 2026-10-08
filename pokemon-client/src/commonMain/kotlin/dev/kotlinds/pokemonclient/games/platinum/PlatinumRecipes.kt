package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.games.gen4.Gen4Recipes

/**
 * Platinum's recipes ([PlatinumGame.recipes]): the Gen 4 ones, where only what this game carries out differently is
 * overridden (a procedure that differs: another order of screens, a list menu instead of the touch screen; a datum
 * that differs goes through [dev.kotlinds.pokemonclient.PokemonGame] or the typed state instead). Nothing yet.
 */
internal object PlatinumRecipes : Gen4Recipes()
