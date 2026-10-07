package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionError
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.Availability
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.PlanContext
import dev.kotlinds.pokemonclient.actions.Recipes
import dev.kotlinds.pokemonclient.actions.Step
import dev.kotlinds.pokemonclient.actions.UnavailableReason
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A game written outside the `pokemon-client` module (this test source set is another module: it sees only the
 * library's public API, never its `internal` members), the way someone writes their own game in their own project
 * before contributing it: its own instance of the common [Recipes], overriding a shared step ([Recipes.openParty]) and
 * an availability method ([Recipes.saveGameAvailability]), both `protected open`. The overrides are played by the
 * library's own entry points ([ActionRegistry]: the listing and the execution), never called by the test.
 */
class ExternalGameRecipesTest {

    /** A scripted game: always walking with two Pokémon; records the buttons pressed. */
    private class ExternalGame : PokemonGame {
        val presses = mutableListOf<Button>()

        /** Every party opened through this game's own step. */
        var partiesOpened = 0

        override val name = "External"

        override fun state(memory: Memory) = GameState(
            0, Screen.Overworld(awaiting = Awaiting.INPUT), null, listOf(mon(1), mon(2)), null, null, null,
        )

        private var held: Set<Button> = emptySet()
        override val inputProbe = InputProbe { held }

        /**
         * The game's own recipes: the common ones where it plays the same, its own party step and save condition. No
         * global shared object: an instance of its own.
         */
        override val recipes: Recipes = object : Recipes() {
            /** This game opens its party with SELECT (a step every party recipe goes through). */
            override fun openParty(context: PlanContext): Step<GameState> {
                partiesOpened++
                context.scope.tap(Button.SELECT)
                return Step.Failed(ActionError.UnexpectedScreen("the external game's party", context.state().screen))
            }

            /** This game saves only at its save points: never from the field. */
            override fun saveGameAvailability(state: GameState): Availability =
                Availability.Unavailable(UnavailableReason.CANNOT_USE_HERE, "this game saves at its save points only", "go to a save point")
        }

        val console = object : ConsolePort {
            override val platform = Platform.NINTENDO_DS
            override var frame = 0L
            override val revision get() = frame
            override fun step(frames: Int, input: InputFrame) = repeat(frames) {
                frame++
                presses += input.buttons - held
                held = input.buttons
            }
            override fun memorySize(region: MemoryRegion) = 16
            override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
            override fun framebuffer(): Frame? = null
            override fun saveState() = ByteArray(0)
            override fun loadState(state: ByteArray) = true
        }

        fun scope() = ActionScope(console, inputProbe)

        private fun mon(n: Long) = PartyMon(
            MonId(n, 1), n.toInt() - 1, Named(SpeciesId(155), "CYNDAQUIL"), null, 5, 20, 20, null, listOf("Fire"), null, null, emptyList(), emptyMap(), 0, null, false,
        )
    }

    private val registry = ActionRegistry.of()

    /** The game's own party step is played by a common recipe (`reorder_party`), run through the registry. */
    @Test
    fun anExternalGamesOwnStepIsPlayedByTheCommonRecipes() {
        val game = ExternalGame()
        val outcome = registry.execute(GameAction.ReorderParty(MonId(2, 1), 1), game.scope(), game)
        val error = assertIs<ActionError.UnexpectedScreen>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals("the external game's party", error.expected)
        assertEquals(1, game.partiesOpened)
        assertEquals(listOf(Button.SELECT), game.presses, "its own way, never the common start menu (X)")
    }

    /** The game's own condition decides the listing and the execution alike; another game keeps the common rule. */
    @Test
    fun anExternalGamesOwnConditionIsAppliedByTheListingAndTheExecution() {
        val game = ExternalGame()
        val state = game.state(game.scope().memory())
        assertTrue(registry.available(state, ActionMode.ASSISTED, game).none { it.name == "save_game" })
        val listed = registry.unavailable(state, ActionMode.ASSISTED, game).single { it.name == "save_game" }
        assertEquals(UnavailableReason.CANNOT_USE_HERE, listed.reason)
        assertEquals("this game saves at its save points only", listed.detail)
        val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.SaveGame, game.scope(), game)).error)
        assertEquals(UnavailableReason.CANNOT_USE_HERE to "this game saves at its save points only", refused.reason to refused.detail)
        assertTrue(game.presses.isEmpty(), "refused before any press")

        // The same game with the common recipes, instantiated as they are: save_game offered in the field.
        val common = object : PokemonGame by game {
            override val recipes = Recipes()
        }
        assertTrue(registry.available(state, ActionMode.ASSISTED, common).any { it.name == "save_game" })
    }
}
