package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.dialogue
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.field
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.item
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.yesNo
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.games.hgss.HgssFixtures
import dev.kotlinds.pokemonclient.games.hgss.HgssGame
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.platinum.PlatinumFixtures
import dev.kotlinds.pokemonclient.games.platinum.PlatinumGame
import dev.kotlinds.pokemonclient.games.platinum.PlatinumVersion
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.PokegearRadio
import dev.kotlinds.pokemonclient.state.RadioBand
import dev.kotlinds.pokemonclient.state.RadioChannel
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import kotlin.reflect.KFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * When an action can run is one method per action in the game's recipes ([ActionDefinition.availability]), read by
 * the listing ([ActionRegistry.available], [ActionRegistry.unavailable], [ActionRegistry.enumerate]) and by the
 * execution ([ActionRegistry.execute]) alike: an action is never listed but refused, nor accepted but not listed
 * (other than an explicit `listed = false`). A game that overrides a condition sees it applied on both sides; the
 * other games keep the common one.
 */
class AvailabilityTest {

    private val registry = ActionRegistry.of()

    private val tackle = KnownMove(Named(MoveId(33), "Tackle"), 35, 35, "Normal")

    private fun battler(ref: BattlerRef) = BattlerState(
        ref, if (ref.isPlayerSide) MonId(1, 1) else null, Named(SpeciesId(155), "CYNDAQUIL"), null, 5, 20, 20, null, emptySet(), emptyMap(), listOf("Fire"), listOf(tackle),
    )

    private fun battle(kind: BattleKind) =
        BattleState(kind, false, BattlerRef.PLAYER_LEFT, listOf(battler(BattlerRef.PLAYER_LEFT), battler(BattlerRef.FOE_LEFT)), emptyList(), emptyList(), null)

    private val command = Screen.BattleCommand(
        BattlerRef.PLAYER_LEFT, listOf("fight", "bag", "run", "pokemon").map { Entry("option:$it", it.uppercase()) }, Cursor.At(0), Topology.vertical(4),
    )

    private val bag = listOf(
        BagPocket("items", listOf(item(79, "Repel", 2))),
        BagPocket("medicine", listOf(item(17, "Potion", 3))),
        BagPocket("balls", listOf(item(4, "Poké Ball", 5))),
        BagPocket("tms_hms", listOf(item(328, "TM01"))),
        BagPocket("key_items", listOf(item(446, "Good Rod"), item(450, "Bicycle"))),
    )

    /** The map: a nurse, a clerk, a Strength boulder. */
    private val objects = listOf(
        FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.NURSE),
        FieldObject("person:1", "clerk", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH, role = PersonRole.CLERK),
        FieldObject("person:2", "boulder", FieldObjectKind.OBSTACLE, 5, 5, Direction.SOUTH, obstacle = ObstacleKind.BOULDER),
    )

    private val player = PlayerInfo("ACE", 3000, emptyList(), 1, pokegearCards = setOf(PokegearCard.MAP, PokegearCard.RADIO))

    private fun radio() = Screen.Viewer(
        ViewerApp.POKEGEAR_RADIO, ViewerExit(touch = TouchPoint(230, 176)), Awaiting.INPUT,
        radio = PokegearRadio(RadioBand.JOHTO, listOf(RadioChannel(0, listOf(RadioStation.POKEMON_MUSIC), TouchPoint(112, 76), 4, 16)), TouchPoint(112, 76), 0, true, RadioStation.POKEMON_MUSIC),
    )

    private fun state(screen: Screen, field: Boolean = true, battle: BattleState? = null, player: PlayerInfo? = this.player) =
        GameState(0, screen, player, listOf(mon(1, listOf(tackle)), mon(2)), bag, battle, if (field) field(2, 2, Direction.NORTH, objects) else null)

    private val overworld = Screen.Overworld(awaiting = Awaiting.INPUT)

    /** States covering the screens the actions start from; each waits for input (nothing to settle). */
    private val states: Map<String, GameState> = mapOf(
        "walking" to state(overworld),
        "walking, nothing unlocked" to state(overworld).copy(startMenu = emptySet()),
        "walking, no Radio Card" to state(overworld, player = player.copy(pokegearCards = setOf(PokegearCard.MAP))),
        "walking, no Pokégear" to state(overworld).copy(pokegear = false),
        "walking, a Pokégear" to state(overworld).copy(pokegear = true),
        "radio" to state(radio()).copy(pokegear = true),
        "start menu" to state(Screen.ListMenu(MenuKind.START_MENU, listOf("option:pokemon", "option:bag", "option:save").map { Entry(it, it) }, Cursor.At(0), Topology.vertical(3))),
        "message" to state(dialogue("Hello!")),
        "yes / no" to state(yesNo("Sure?")),
        "keyboard" to state(Screen.Keyboard("pokemon", "upper", "", 10, listOf(Entry("option:ok", "OK")), Cursor.At(0), Topology.vertical(1))),
        "wild battle" to state(command, field = false, battle = battle(BattleKind.WILD)),
        "wild battle, no Pokégear" to state(command, field = false, battle = battle(BattleKind.WILD)).copy(pokegear = false),
        "trainer battle" to state(command, field = false, battle = battle(BattleKind.TRAINER)),
        "title screen" to state(Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.INPUT), field = false, player = null),
    )

    /** A game with [recipes] whose screen is [state]'s (the state decoded on every frame). */
    private fun game(state: GameState, recipes: Recipes = Recipes()) =
        FakeGame(state.screen, state = { state.copy(screen = it) }).also { it.recipes = recipes }

    /**
     * For every action type and every state above, in each mode: the listing places the action exactly where the
     * availability the execution checks says (available and listed, unavailable with the same reason, or not listed:
     * hidden, not in this game, or accepted without being offered), and the execution refuses exactly what isn't
     * available, before pressing anything: with the listed reason, as a wrong screen when hidden, as not supported by
     * the game (with its detail) when the game doesn't have the action.
     */
    @Test
    fun theListingAndTheExecutionAgreeForEveryActionAndState() {
        for ((name, state) in states) for (mode in ActionMode.entries) {
            val game = game(state)
            val available = registry.available(state, mode, game).map { it.name }.toSet()
            val unavailable = registry.unavailable(state, mode, game).associateBy { it.name }
            val enumerated = registry.enumerate(state, mode, game).values.map { it::class }.toSet()
            for (def in CommonActions.definitions.filter { mode in it.spec.modes }) {
                val action = def.spec.name
                val toRun = registry.availabilityToRun(def, game.context())
                val where = "$action on \"$name\" ($mode): $toRun"
                when (toRun) {
                    is Availability.Available -> {
                        assertEquals(toRun.listed, action in available, where)
                        assertTrue(action !in unavailable, where)
                    }
                    is Availability.Unavailable -> {
                        assertTrue(action !in available, where)
                        assertEquals(toRun.reason, unavailable[action]?.reason, where)
                    }
                    Availability.Hidden -> assertTrue(action !in available && action !in unavailable, where)
                    is Availability.NotInThisGame -> assertTrue(action !in available && action !in unavailable, where)
                }
                // Nothing enumerated that the execution would refuse.
                if (def.type in enumerated) assertIs<Availability.Available>(toRun, where)
                // What isn't listed at all is refused too, each with its own reason.
                val sample = sample(action) ?: continue
                val expected = when (toRun) {
                    Availability.Hidden -> UnavailableReason.WRONG_SCREEN to "$action isn't possible on this screen"
                    is Availability.NotInThisGame -> UnavailableReason.NOT_SUPPORTED_BY_GAME to toRun.detail
                    else -> continue
                }
                val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(sample, game.scope(), game), where).error, where)
                assertEquals(expected, refused.reason to refused.detail, where)
            }
            // The execution refuses what isn't available, with the listed reason, without a press.
            for ((action, listed) in unavailable) {
                val sample = sample(action) ?: continue
                val refused = assertIs<ActionOutcome.Failed>(registry.execute(sample, game.scope(), game), "$action on \"$name\"")
                assertEquals(listed.reason, assertIs<ActionError.Unavailable>(refused.error).reason, "$action on \"$name\"")
            }
            assertTrue(game.presses.isEmpty(), "\"$name\": ${game.presses}")
        }
    }

    /** One instance of the actions refused above (any parameters: the refusal comes before them). */
    private fun sample(action: String): GameAction? = when (action) {
        "tune_radio" -> TuneRadio(RadioStation.POKEMON_MUSIC)
        "run" -> GameAction.Run
        "save_game" -> GameAction.SaveGame
        "set_options" -> GameAction.SetOptions()
        "open_menu" -> GameAction.OpenMenu("option:bag")
        else -> null
    }

    /**
     * Every action's availability is its own method of the recipes (`<action>Availability`), never another
     * action's: the one entry point the listing and the execution read.
     */
    @Test
    fun eachActionReadsItsOwnAvailabilityMethod() {
        for (def in CommonActions.definitions) {
            val camel = def.spec.name.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")
            assertEquals("${camel}Availability", (def.availability as KFunction<*>).name, def.spec.name)
        }
    }

    /** A game whose save is refused (its own `saveGameAvailability`): a condition overridden, as a game would. */
    private class NoSaveRecipes : Recipes() {
        /** Test game: the start menu has no SAVE in this "game" (an override, documented as every override must be). */
        override fun saveGameAvailability(state: GameState): Availability =
            Availability.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "this game saves elsewhere")
    }

    /** A game without a save menu at all (its own `saveGameAvailability`): the action doesn't exist in it. */
    private class NoSaveMenuRecipes : Recipes() {
        /** Test game: this "game" has no way to save (an override, documented as every override must be). */
        override fun saveGameAvailability(state: GameState): Availability = Availability.NotInThisGame("this game can't be saved")
    }

    /**
     * An action a game doesn't have ([Availability.NotInThisGame], here its own override) is listed nowhere and refused
     * as not supported by the game, with its detail, before any press; another game keeps the common rule.
     */
    @Test
    fun anActionNotInTheGameIsNeverListedAndRefusedAsNotSupported() {
        val walking = state(overworld)
        val game = game(walking, NoSaveMenuRecipes())
        assertTrue(registry.available(walking, ActionMode.ASSISTED, game).none { it.name == "save_game" })
        assertTrue(registry.unavailable(walking, ActionMode.ASSISTED, game).none { it.name == "save_game" })
        assertTrue(registry.enumerate(walking, ActionMode.ASSISTED, game).values.none { it is GameAction.SaveGame })
        val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.SaveGame, game.scope(), game)).error)
        assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME to "this game can't be saved", refused.reason to refused.detail)
        assertTrue(game.presses.isEmpty() && game.touches.isEmpty())
        assertTrue(registry.available(walking, ActionMode.ASSISTED, game(walking)).any { it.name == "save_game" })
    }

    /**
     * Not in this game is not to be confused with hidden: refused at once, even while the game is busy (nothing to
     * settle for: no frame runs), where a hidden action waits for the game to settle and is refused as a wrong screen.
     */
    @Test
    fun anActionNotInTheGameIsRefusedAtOnceAndAHiddenOneAsAWrongScreen() {
        val busy = state(Screen.Overworld(awaiting = Awaiting.ANIMATION))
        val game = game(busy, NoSaveMenuRecipes())
        val scope = game.scope()
        val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.SaveGame, scope, game)).error)
        assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME, refused.reason)
        assertEquals(0L, scope.framesUsed, "refused without letting the game run")

        // Hidden (run outside a battle): unchanged, a wrong screen, not listed either.
        val walking = state(overworld)
        val common = game(walking)
        assertEquals(Availability.Hidden, registry.availabilityToRun(CommonActions.run, common.context()))
        assertTrue(registry.unavailable(walking, ActionMode.ASSISTED, common).none { it.name == "run" })
        val wrong = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.Run, common.scope(), common)).error)
        assertEquals(UnavailableReason.WRONG_SCREEN, wrong.reason)
        assertTrue(common.presses.isEmpty())
    }

    /** A game's own condition is applied by the listing and by the execution; other games keep the common one. */
    @Test
    fun aGamesOwnConditionIsAppliedByTheListingAndTheExecutionForThatGameOnly() {
        val walking = state(overworld)
        val overriding = game(walking, NoSaveRecipes())
        assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME, registry.unavailable(walking, ActionMode.ASSISTED, overriding).single { it.name == "save_game" }.reason)
        assertTrue(registry.available(walking, ActionMode.ASSISTED, overriding).none { it.name == "save_game" })
        val refused = assertIs<ActionOutcome.Failed>(registry.execute(GameAction.SaveGame, overriding.scope(), overriding))
        assertEquals("this game saves elsewhere", assertIs<ActionError.Unavailable>(refused.error).detail)
        assertTrue(overriding.presses.isEmpty())
        // Another game, the same state: the common rule (in the field: available).
        val other = game(walking)
        assertTrue(registry.available(walking, ActionMode.ASSISTED, other).any { it.name == "save_game" })
        assertTrue(registry.unavailable(walking, ActionMode.ASSISTED, other).none { it.name == "save_game" })
        assertIs<Availability.Available>(registry.availabilityToRun(CommonActions.saveGame, other.context()))
    }

    /**
     * `tune_radio` in a game without a Pokégear (the state says so, [GameState.pokegear]: Platinum) doesn't exist
     * ([Availability.NotInThisGame]): listed nowhere, on any screen, and refused by the execution as not supported by
     * the game (never as a wrong screen, even in a battle), before any press; in a game with one, it is accepted from
     * the field (opening the Pokégear) and offered on the radio.
     */
    @Test
    fun tuneRadioDoesntExistWithoutAPokegearAndIsAcceptedWithOne() {
        for (noGear in listOf(state(overworld, player = null), state(command, field = false, battle = battle(BattleKind.WILD))).map { it.copy(pokegear = false) }) {
            val platinumLike = game(noGear)
            val where = noGear.screen::class.simpleName
            assertTrue(registry.unavailable(noGear, ActionMode.ASSISTED, platinumLike).none { it.name == "tune_radio" }, where)
            assertTrue(registry.available(noGear, ActionMode.ASSISTED, platinumLike).none { it.name == "tune_radio" }, where)
            assertTrue(registry.enumerate(noGear, ActionMode.ASSISTED, platinumLike).values.none { it is TuneRadio }, where)
            val refused = assertIs<ActionOutcome.Failed>(registry.execute(TuneRadio(RadioStation.POKE_FLUTE), platinumLike.scope(), platinumLike), where)
            val error = assertIs<ActionError.Unavailable>(refused.error, where)
            assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME, error.reason, where)
            assertEquals("This game has no Pokégear (so no radio)", error.detail, where)
            assertTrue(platinumLike.presses.isEmpty() && platinumLike.touches.isEmpty(), where)
        }

        // A game with a Pokégear and its Radio Card: accepted from the field (not offered there), offered on the radio.
        val walking = state(overworld).copy(pokegear = true)
        val hgssLike = game(walking)
        assertEquals(false, assertIs<Availability.Available>(registry.availabilityToRun(PokegearActions.tuneRadio, hgssLike.context())).listed)
        assertTrue(registry.unavailable(walking, ActionMode.ASSISTED, hgssLike).none { it.name == "tune_radio" })
        val onRadio = state(radio()).copy(pokegear = true)
        assertTrue(registry.available(onRadio, ActionMode.ASSISTED, game(onRadio)).any { it.name == "tune_radio" })
        // Without its Radio Card yet: refused, typed (the story gives it).
        val noCard = state(overworld, player = player.copy(pokegearCards = setOf(PokegearCard.MAP))).copy(pokegear = true)
        assertEquals(UnavailableReason.NOT_UNLOCKED_YET, registry.unavailable(noCard, ActionMode.ASSISTED, game(noCard)).single { it.name == "tune_radio" }.reason)
    }

    /** The real games: Platinum's state says it has no Pokégear, HeartGold's that it has one (RAM fixtures, no ROM). */
    @Test
    fun theGamesSayWhetherTheyHaveAPokegear() {
        val platinum = PlatinumGame(PlatinumVersion.PLATINUM_US)
        val bedroom = platinum.state(PlatinumFixtures.load("pt_bedroom"))
        assertEquals(false, bedroom.pokegear)
        // Platinum has no Pokégear: tune_radio is listed nowhere (not even unavailable).
        assertTrue(registry.unavailable(bedroom, ActionMode.ASSISTED, platinum).none { it.name == "tune_radio" })
        assertTrue(registry.available(bedroom, ActionMode.ASSISTED, platinum).none { it.name == "tune_radio" })
        // Executed with Platinum's own recipes on its state: refused as not supported by the game, before any press.
        val played = FakeGame(bedroom.screen, state = { bedroom.copy(screen = it) }).also { it.recipes = platinum.recipes }
        val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(TuneRadio(RadioStation.POKE_FLUTE), played.scope(), played)).error)
        assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME, refused.reason)
        assertEquals("This game has no Pokégear (so no radio)", refused.detail)
        assertTrue(played.presses.isEmpty() && played.touches.isEmpty())

        val hgss = HgssGame(HgssVersion.HEARTGOLD_US)
        val radio = hgss.state(HgssFixtures.load("gr_radio_johto_music"))
        assertEquals(true, radio.pokegear)
        assertTrue(registry.available(radio, ActionMode.ASSISTED, hgss).any { it.name == "tune_radio" })
    }
}
