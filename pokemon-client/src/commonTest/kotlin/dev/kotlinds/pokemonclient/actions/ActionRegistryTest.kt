package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.games.hgss.HgssGame
import dev.kotlinds.pokemonclient.games.hgss.HgssRecipes
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.gen4.Gen4Recipes
import dev.kotlinds.pokemonclient.games.platinum.PlatinumGame
import dev.kotlinds.pokemonclient.games.platinum.PlatinumRecipes
import dev.kotlinds.pokemonclient.games.platinum.PlatinumVersion
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActionRegistryTest {

    private val registry = ActionRegistry.of()

    private val tackle = KnownMove(Named(MoveId(33), "Tackle"), 35, 35, "Normal")
    private val ember = KnownMove(Named(MoveId(52), "Ember"), 0, 25, "Fire")

    private fun battler(ref: BattlerRef, moves: List<KnownMove> = emptyList()) = BattlerState(
        ref, if (ref.isPlayerSide) MonId(1, 2) else null, Named(SpeciesId(155), "CYNDAQUIL"), null, 5, 20, 20, null, emptySet(), emptyMap(), listOf("Fire"), moves,
    )

    private fun battleState(screen: Screen, kind: BattleKind = BattleKind.WILD) = GameState(
        0, screen, null, emptyList(), null,
        BattleState(kind, false, BattlerRef.PLAYER_LEFT, listOf(battler(BattlerRef.PLAYER_LEFT, listOf(tackle, ember)), battler(BattlerRef.FOE_LEFT)), emptyList(), emptyList(), null),
        null,
    )

    private val command = Screen.BattleCommand(
        BattlerRef.PLAYER_LEFT,
        listOf("fight" to "FIGHT", "bag" to "BAG", "run" to "RUN", "pokemon" to "POKéMON").map { (id, l) -> Entry("option:$id", l) },
        Cursor.At(0),
        Topology.of(mapOf(0 to mapOf(Button.LEFT to 1, Button.DOWN to 2, Button.RIGHT to 3), 1 to mapOf(Button.UP to 0, Button.RIGHT to 2), 2 to mapOf(Button.UP to 0, Button.LEFT to 1, Button.RIGHT to 3), 3 to mapOf(Button.UP to 0, Button.LEFT to 2))),
    )

    private val moves = Screen.MoveSelect(
        MoveContext.BATTLE, MonId(1, 2), null,
        listOf(Entry("move:33", "Tackle (Normal, 35/35 PP)"), Entry("move:52", "Ember (Fire, 0/25 PP)", selectable = false), Entry("empty:2", "-", false), Entry("empty:3", "-", false), Entry("option:cancel", "CANCEL")),
        Cursor.At(0), Topology.grid(4, 2), CancelBehavior.CLOSES,
    )

    @Test
    fun battleActionsAreListedOnlyInBattleWithUsableMoves() {
        val available = registry.available(battleState(command), ActionMode.ASSISTED).associateBy { it.name }
        assertEquals(listOf("move:33"), available.getValue("attack").choices.getValue("move").map { it.value })
        assertTrue("run" in available)
        val field = GameState(0, Screen.Overworld(awaiting = Awaiting.INPUT), null, emptyList(), null, null, null)
        assertTrue(registry.available(field, ActionMode.ASSISTED).none { it.name == "attack" || it.name == "run" })
    }

    @Test
    fun theFieldActionsAreListedDuringTheFadeBackToTheField() {
        // Right after a battle the overworld is back but still animating: what `act` accepts (it waits for the game)
        // is listed already (NOTES: `reorder_party` missing, then accepted a second later).
        fun field(awaiting: Awaiting) = GameState(0, Screen.Overworld(awaiting = awaiting), null, emptyList(), null, null, null)
        val ready = registry.available(field(Awaiting.INPUT), ActionMode.ASSISTED).map { it.name }.toSet()
        val fading = registry.available(field(Awaiting.ANIMATION), ActionMode.ASSISTED).map { it.name }.toSet()
        assertTrue(ready.isNotEmpty() && fading.containsAll(ready), "missing while fading: ${ready - fading}")
        assertTrue(registry.unavailable(field(Awaiting.ANIMATION), ActionMode.ASSISTED).none { it.name in ready })
        // A battle still animating isn't projected: what comes next there isn't known.
        assertTrue(registry.available(battleState(Screen.Battle(Awaiting.ANIMATION)), ActionMode.ASSISTED).none { it.name == "attack" })
    }

    // region Per-game recipes

    /** A game's recipes whose `wait` only says it ran (its own recipe in place of the common one). */
    private fun ownWait() = object : Recipes() {
        override fun wait(action: GameAction.Wait, context: PlanContext) = ActionOutcome.Done("the game's own wait")
    }

    /**
     * A game's own recipe ([dev.kotlinds.pokemonclient.PokemonGame.recipes], a subclass overriding one method) is
     * played for that game only: through the registry like every host, and when the walking engine carries the
     * action out as one of its steps (on [PlanContext.recipes]); a game without it plays the common one.
     */
    @Test
    fun aGamesOwnRecipeReplacesTheCommonOneForThatGameOnly() {
        val overriding = FakeGame(Screen.Overworld(null, Awaiting.INPUT)).apply { recipes = ownWait() }
        val done = assertIs<ActionOutcome.Done>(registry.execute(GameAction.Wait(frames = 10), overriding.scope(), overriding))
        assertEquals("the game's own wait", done.detail)
        assertEquals(0L, overriding.console.frame, "the common recipe would have waited 10 frames")
        // As a step carried out by the engine (on the context's recipes): the game's own one too.
        val step = overriding.context()
        assertEquals("the game's own wait", assertIs<ActionOutcome.Done>(step.recipes.perform(GameAction.Wait(frames = 10), step)).detail)
        assertEquals(0L, overriding.console.frame)
        // The same action in a game without its own recipe: the common one, never the other game's.
        val plain = FakeGame(Screen.Overworld(null, Awaiting.INPUT))
        val common = assertIs<ActionOutcome.Done>(registry.execute(GameAction.Wait(frames = 10), plain.scope(), plain))
        assertTrue(common.detail != "the game's own wait")
        assertTrue(plain.console.frame >= 10L, "frame ${plain.console.frame}")
        val plainStep = plain.context()
        assertTrue(assertIs<ActionOutcome.Done>(plainStep.recipes.perform(GameAction.Wait(frames = 10), plainStep)).detail != "the game's own wait")
        assertTrue(plain.console.frame >= 20L, "frame ${plain.console.frame}")
    }

    /**
     * Each Gen 4 game plays its own recipes, built on the Gen 4 ones ([Gen4Recipes]), one instance shared by every
     * game object (recipes are stateless); a game that gives none plays the common ones.
     */
    @Test
    fun eachGen4GamePlaysItsOwnRecipesOnTheGen4Ones() {
        val hgss = HgssGame(HgssVersion.HEARTGOLD_US).recipes
        val platinum = PlatinumGame(PlatinumVersion.PLATINUM_US).recipes
        assertIs<HgssRecipes>(hgss)
        assertIs<PlatinumRecipes>(platinum)
        assertSame(hgss, HgssGame(HgssVersion.HEARTGOLD_US).recipes)
        assertTrue(hgss !== platinum)
        val withoutOwnRecipes = object : dev.kotlinds.pokemonclient.PokemonGame {
            override val name = "none"
            override fun state(memory: dev.kotlinds.pokemonclient.Memory): GameState = error("not read")
            override val inputProbe = dev.kotlinds.pokemonclient.runtime.InputProbe { emptySet() }
        }
        assertSame(Recipes.COMMON, withoutOwnRecipes.recipes)
    }

    /**
     * One instance of every action type ([GameAction] is sealed: [RecipeBase.perform] can't compile without a recipe
     * for each, but a spec is found by type at run time). Kept by hand: a new action type adds its witness here.
     */
    private val witnesses: List<GameAction> = listOf(
        GameAction.Press(Button.A), GameAction.Touch(TouchPoint(1, 1)), GameAction.Wait(1), GameAction.Drag(TouchPoint(1, 1), TouchPoint(2, 2)),
        GameAction.AdvanceDialogue, GameAction.Choose("option:yes"), GameAction.EnterText("ABC"),
        GameAction.Attack(MoveRef("move:33")), GameAction.Switch(MonId(1, 2)), GameAction.KeepBattling, GameAction.Run,
        GameAction.ThrowBall(ItemRef("item:4")), GameAction.LearnMove(null),
        GameAction.UseItem(ItemRef("item:17")), GameAction.Teach(ItemRef("item:328"), MonId(1, 2)), GameAction.ReorderParty(MonId(1, 2), 1),
        GameAction.GiveItem(MonId(1, 2), ItemRef("item:17")), GameAction.TakeItem(MonId(1, 2)), GameAction.UseKeyItem(ItemRef("item:450")),
        GameAction.RegisterItem(ItemRef("item:450")),
        GameAction.GoTo(1, 1, null), GameAction.Interact("person:0"), GameAction.Step(Direction.NORTH), GameAction.FindEncounter,
        GameAction.Push("person:0"),
        GameAction.Heal, GameAction.Pc(emptyList()), GameAction.Deposit(MonId(1, 2)), GameAction.Withdraw(MonId(1, 2)),
        GameAction.Release(MonId(1, 2), confirm = true), GameAction.Buy(emptyList()), GameAction.Sell(ItemRef("item:17"), 1),
        GameAction.SetQuantity(1),
        GameAction.Fly("New Bark Town"), GameAction.Fish(ItemRef("item:445")), GameAction.UseFieldMove(FieldMoveKind.CUT),
        GameAction.SaveGame, GameAction.SetOptions(), GameAction.OpenMenu("option:bag"), TuneRadio(RadioStation.entries.first()),
        GameAction.SoftReset, GameAction.ContinueGame, GameAction.ChooseStarter("starter:0"), GameAction.WatchHallOfFame,
    )

    /**
     * Every action type has exactly one spec (what agents see), and every spec one action type: the contract the
     * recipes carry out ([RecipeBase.perform] is complete by construction, the specs are checked here).
     */
    @Test
    fun everyActionTypeHasExactlyOneSpec() {
        assertEquals(witnesses.size, witnesses.map { it::class }.toSet().size, "one witness per action type")
        for (action in witnesses) {
            assertEquals(1, CommonActions.definitions.count { it.type.isInstance(action) }, "specs of ${action::class.simpleName}")
        }
        assertEquals(witnesses.size, CommonActions.definitions.size, "a spec without a witness: add it to the witnesses")
        assertEquals(CommonActions.definitions.size, CommonActions.definitions.map { it.spec.name }.toSet().size, "two specs with one name")
    }

    // endregion

    // region executeAndSettle: every host's step (the app's sessions, the MCP, the bench)

    /** A fake game busy (animating) until frame [readyAt], then waiting for input. */
    private fun busyUntil(readyAt: Long) = FakeGame(Screen.Overworld(null, Awaiting.ANIMATION)).apply {
        onFrame = { frame, _ -> Screen.Overworld(null, if (frame < readyAt) Awaiting.ANIMATION else Awaiting.INPUT) }
    }

    @Test
    fun aStepSettlesUntilTheGameWaitsForInput() {
        val game = busyUntil(300)
        assertIs<ActionOutcome.Done>(registry.executeAndSettle(GameAction.Wait(frames = 10), game.scope(), game))
        assertEquals(Awaiting.INPUT, game.screen.awaiting)
        assertTrue(game.console.frame in 300L until ActionRegistry.STEP_FRAMES, "frame ${game.console.frame}")
    }

    @Test
    fun aLongActionStillLeavesTheMinimumToSettle() {
        // The action used the whole step's budget: the game is still given MIN_SETTLE_FRAMES to settle.
        val game = busyUntil(ActionRegistry.STEP_FRAMES + 100L)
        registry.executeAndSettle(GameAction.Wait(frames = ActionRegistry.STEP_FRAMES), game.scope(), game)
        assertEquals(Awaiting.INPUT, game.screen.awaiting)
    }

    @Test
    fun aGameThatNeverSettlesEndsTheStepAtItsBudget() {
        val game = busyUntil(Long.MAX_VALUE)
        registry.executeAndSettle(GameAction.Wait(frames = 10), game.scope(), game)
        assertEquals(Awaiting.ANIMATION, game.screen.awaiting)
        assertTrue(game.console.frame in ActionRegistry.STEP_FRAMES.toLong()..ActionRegistry.STEP_FRAMES + 10L, "frame ${game.console.frame}")
    }

    // endregion

    @Test
    fun runIsUnavailableInTrainerBattlesWithATypedReason() {
        val unavailable = registry.unavailable(battleState(command, BattleKind.TRAINER), ActionMode.ASSISTED).single { it.name == "run" }
        assertEquals(UnavailableReason.TRAINER_BATTLE, unavailable.reason)
    }

    @Test
    fun pureModeOnlyOffersRawControls() {
        val names = registry.available(battleState(command), ActionMode.PURE).map { it.name }.toSet()
        assertEquals(setOf("press", "touch", "wait", "drag"), names)
    }

    @Test
    fun parsingRejectsUnknownTypesAndBadParameters() {
        val unknown = registry.parse(buildJsonObject { put("type", "teleport") }, ActionMode.ASSISTED)
        assertIs<ActionError.InvalidParameter>((unknown.exceptionOrNull() as ActionException).error)
        val badButton = registry.parse(buildJsonObject { put("type", "press"); put("button", "z") }, ActionMode.ASSISTED)
        assertIs<ActionError.InvalidParameter>((badButton.exceptionOrNull() as ActionException).error)
        val pureAttack = registry.parse(buildJsonObject { put("type", "attack"); put("move", "tackle") }, ActionMode.PURE)
        assertTrue(pureAttack.isFailure)
        val ok = registry.parse(buildJsonObject { put("type", "attack"); put("move", JsonPrimitive("Tackle")) }, ActionMode.ASSISTED)
        assertEquals(GameAction.Attack(MoveRef("Tackle")), ok.getOrThrow())
    }

    @Test
    fun anUnknownKeyInsideAnArrayParameterNamesTheKeyToUse() {
        // NOTES (map randomizer run): a `pc` operation without `op` got "Invalid op `missing`", which never said the key.
        fun pc(operation: String) = registry.parse(kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"type\":\"pc\",\"operations\":[{\"op\":\"withdraw\",\"pokemon\":\"mon:00000001.00000002\"}, $operation]}").let { it as kotlinx.serialization.json.JsonObject }, ActionMode.ASSISTED)
        val error = assertIs<ActionError.UnknownParameter>((pc("{\"action\":\"deposit\",\"pokemon\":\"mon:00000001.00000002\"}").exceptionOrNull() as ActionException).error)
        assertEquals("action", error.name)
        assertEquals("operations[1]", error.within)
        assertEquals(listOf("op"), error.suggested.map { it.first })
        assertTrue(error.message.startsWith("Unknown key `action` in operations[1] of pc: use `op` ("), error.message)
        // Right keys: parsed as before, every operation.
        val ok = pc("{\"op\":\"swap\",\"pokemon\":\"mon:00000001.00000002\",\"with\":\"mon:00000003.00000004\"}").getOrThrow()
        assertEquals(2, assertIs<GameAction.Pc>(ok).operations.size)
        // The same check for the items of use_item and buy.
        val use = registry.parse(kotlinx.serialization.json.Json.parseToJsonElement("{\"type\":\"use_item\",\"item\":\"Potion\",\"items\":[{\"item\":\"Potion\",\"pokemon\":\"mon:00000001.00000002\"}]}") as kotlinx.serialization.json.JsonObject, ActionMode.ASSISTED)
        val useError = assertIs<ActionError.UnknownParameter>((use.exceptionOrNull() as ActionException).error)
        assertEquals("pokemon", useError.name)
        assertEquals(listOf("target", "move"), useError.suggested.map { it.first })
    }

    @Test
    fun onRepelEndIsTypedAndStopsByDefault() {
        fun goTo(extra: String) = registry.parse(kotlinx.serialization.json.Json.parseToJsonElement("{\"type\":\"go_to\",\"x\":3,\"y\":4$extra}") as kotlinx.serialization.json.JsonObject, ActionMode.ASSISTED)
        assertEquals(RepelEnd.STOP, assertIs<GameAction.GoTo>(goTo("").getOrThrow()).options.onRepelEnd)
        assertEquals(RepelEnd.AUTO, assertIs<GameAction.GoTo>(goTo(",\"on_repel_end\":\"auto\"").getOrThrow()).options.onRepelEnd)
        val error = assertIs<ActionError.InvalidParameter>((goTo(",\"on_repel_end\":\"again\"").exceptionOrNull() as ActionException).error)
        assertEquals(listOf("stop", "continue", "reapply", "auto"), error.allowed)
    }

    @Test
    fun theSchemaDescribesTheKeysOfArrayParameters() {
        val schema = registry.jsonSchema(ActionMode.ASSISTED).toString()
        assertTrue("\"items\":{\"type\":\"object\",\"properties\":{\"op\":" in schema, schema.substringAfter("\"pc\"").take(300))
    }

    @Test
    fun anUnknownParameterNamesTheParameterToUseWithoutAnAlias() {
        // NOTES (map randomizer run): `option` passed twice to open_menu / choose, which take `entry`.
        val menu = registry.parse(buildJsonObject { put("type", "open_menu"); put("option", "option:pokemon") }, ActionMode.ASSISTED)
        val error = assertIs<ActionError.UnknownParameter>((menu.exceptionOrNull() as ActionException).error)
        assertEquals("INVALID_PARAM", error.code)
        assertEquals("option", error.name)
        assertEquals(listOf("entry"), error.suggested.map { it.first })
        assertTrue(error.message.startsWith("Unknown parameter `option` for open_menu: use `entry` ("), error.message)
        val choose = registry.parse(buildJsonObject { put("type", "choose"); put("option", "option:yes") }, ActionMode.ASSISTED)
        assertTrue((choose.exceptionOrNull() as ActionException).error.message.contains("use `entry` (Id of the entry"))
        // An action without parameters says so.
        val save = registry.parse(buildJsonObject { put("type", "save_game"); put("slot", 1) }, ActionMode.ASSISTED)
        assertEquals("Unknown parameter `slot` for save_game: save_game takes no parameter", (save.exceptionOrNull() as ActionException).error.message)
    }

    @Test
    fun attackGoesThroughFightThenTheMoveCheckingEachCursor() {
        val game = FakeGame(command, state = { battleState(it) })
        game.onPress = { button, screen ->
            when {
                screen is Screen.BattleCommand && button == Button.A -> moves
                screen is Screen.MoveSelect && button == Button.A -> Screen.Battle(Awaiting.INPUT)
                else -> screen
            }
        }
        val outcome = registry.execute(GameAction.Attack(MoveRef("tackle")), game.scope(), game)
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(listOf(Button.A, Button.A), game.presses)
    }

    @Test
    fun aMoveWithoutPpOrUnknownIsAnExplicitError() {
        val game = FakeGame(command, state = { battleState(it) })
        game.onPress = { button, screen -> if (screen is Screen.BattleCommand && button == Button.A) moves else screen }
        val outcome = registry.execute(GameAction.Attack(MoveRef("Flamethrower")), game.scope(), game)
        assertIs<ActionError.InvalidParameter>(assertIs<ActionOutcome.Failed>(outcome).error)
    }

    @Test
    fun executingAnUnavailableActionFailsBeforePressingAnything() {
        val game = FakeGame(command, state = { battleState(it, BattleKind.TRAINER) })
        val outcome = registry.execute(GameAction.Run, game.scope(), game)
        assertEquals(UnavailableReason.TRAINER_BATTLE, (assertIs<ActionOutcome.Failed>(outcome).error as ActionError.Unavailable).reason)
        assertTrue(game.presses.isEmpty())
    }

    @Test
    fun anActionRefusedWhileTheGameAnimatesWaitsForItThenRuns() {
        // Right after a battle the game is still busy by itself (a fade, a script ending): the action waits for the
        // screen it needs instead of being refused, without any button pressed meanwhile.
        val game = FakeGame(Screen.Battle(Awaiting.ANIMATION), state = { battleState(it) })
        var readyAt: Long? = null
        game.onFrame = { frame, screen -> if (frame >= ANIMATION_FRAMES && screen is Screen.Battle) command.also { readyAt = frame } else screen }
        var pressedAt: Long? = null
        game.onPress = { _, screen -> pressedAt = pressedAt ?: game.console.frame; screen }
        val outcome = registry.execute(GameAction.Run, game.scope(), game)
        val error = (outcome as? ActionOutcome.Failed)?.error
        assertTrue(error !is ActionError.Unavailable || error.reason != UnavailableReason.WRONG_SCREEN, outcome.toString())
        assertTrue(game.presses.isNotEmpty(), "the action ran once the menu was there")
        assertTrue(pressedAt!! > readyAt!!, "nothing pressed before the screen was ready: $pressedAt vs $readyAt")
    }

    @Test
    fun anActionStillMeaninglessOnceTheGameSettledIsRefusedWithoutPressing() {
        val game = FakeGame(Screen.Battle(Awaiting.ANIMATION), state = { battleState(it) })
        game.onFrame = { frame, screen -> if (frame >= ANIMATION_FRAMES) Screen.Battle(Awaiting.INPUT) else screen }
        val outcome = registry.execute(GameAction.Run, game.scope(), game)
        assertEquals(UnavailableReason.WRONG_SCREEN, (assertIs<ActionOutcome.Failed>(outcome).error as ActionError.Unavailable).reason)
        assertTrue(game.presses.isEmpty())
    }

    @Test
    fun enumerationGivesCanonicalKeysForPickFromAListModels() {
        val keys = registry.enumerate(battleState(command), ActionMode.ASSISTED).keys
        assertTrue("attack(move:33)" in keys)
        assertTrue("run" in keys)
        assertTrue("press(a)" in keys)
    }

    @Test
    fun theSchemaHasOneObjectPerAction() {
        val schema = registry.jsonSchema(ActionMode.ASSISTED).toString()
        assertTrue("\"const\":\"attack\"" in schema)
        assertTrue("\"const\":\"press\"" in schema)
    }
}

/** Frames the fake game animates before it waits for input. */
private const val ANIMATION_FRAMES = 40L

class MatchesRefTest {
    @kotlin.test.Test
    fun idsNamesAccentsAndPunctuation() {
        kotlin.test.assertTrue(matchesRef("item:4", "item", 4, "Poké Ball"))
        kotlin.test.assertTrue(matchesRef("4", "item", 4, "Poké Ball"))
        kotlin.test.assertTrue(matchesRef("Poke Ball", "item", 4, "Poké Ball"))
        kotlin.test.assertTrue(matchesRef("POKE-BALL", "item", 4, "Poké Ball"))
        kotlin.test.assertFalse(matchesRef("Great Ball", "item", 4, "Poké Ball"))
    }
}
