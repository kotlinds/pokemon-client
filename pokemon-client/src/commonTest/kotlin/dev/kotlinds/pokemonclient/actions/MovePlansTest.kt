package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.FieldTrainer
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

/**
 * The walker against a simulated field ([GridGame]): holding a direction for [GridGame.STEP_FRAMES] frames moves the player one tile
 * (when the tile is free), like the real games. [invisibleWalls] are refused by the game although the map allows
 * them; stepping on [battleTile] starts a wild battle.
 */
private class WalkingGame(
    rows: List<String>,
    x: Int,
    y: Int,
    val invisibleWalls: Set<Pair<Int, Int>> = emptySet(),
    val battleTile: Pair<Int, Int>? = null,
    val people: List<FieldObject> = emptyList(),
    /** Stepping here makes a trainer see the player: the player stops, the trainer's approach scene runs. */
    val sightTile: Pair<Int, Int>? = null,
    /**
     * Frames of the trainer's "!" and walk up after [sightTile], during which the overworld stays on screen (an
     * animation) and the held direction does nothing, like the real game; then its intro text.
     */
    val approachFrames: Int = 0,
    /** The trainer id of the one who sees the player at [sightTile] ([FieldState.engagedTrainerId]). */
    val spotterId: Int? = null,
    /** On this tile the game ignores the D-pad (the player can't turn there): a turn that never happens. */
    val frozenTile: Pair<Int, Int>? = null,
    /**
     * A door: walking into it starts a fade ([FADE_FRAMES] frames of a transition screen, the player not moved yet),
     * after which the player stands on it.
     */
    val doorTile: Pair<Int, Int>? = null,
    /** Turning to this direction on this tile makes the trainer [spotterId] see the player (no step needed). */
    val sightOnTurn: Pair<Pair<Int, Int>, Direction>? = null,
    /** The chance of a wild encounter per check on the land encounter tiles (grass, cave floor) when walking: none by default. */
    val landEncounters: Double = 0.0,
    /** [FieldState.autoRun]: the running shoes switched on. */
    val autoRun: Boolean? = null,
    /** Whether the world knows its encounter tables ([landEncounters]) or not (then only the tile kinds tell). */
    val encounterTables: dev.kotlinds.pokemonclient.world.EncounterTables = dev.kotlinds.pokemonclient.world.EncounterTables.DECODED,
    /**
     * [FieldState.repelSteps]: a Repel with that many steps left, the party's lead (level 50) stronger than every wild
     * Pokémon of the map (nothing appears while it works). The count as read when the walk plans.
     */
    val repelSteps: Int? = null,
    /**
     * Arriving here starts a message: [messageQuiet] frames where the field still reads as free while the game
     * ignores the D-pad (a scene that hasn't shown anything yet), then [messageBusy] frames of a busy field, then the
     * box, waiting for A (A closes it). [messageNotice]: which [dev.kotlinds.pokemonclient.state.FieldNotice] it is
     * (null: a scene's message).
     */
    val messageTile: Pair<Int, Int>? = null,
    val messageNotice: dev.kotlinds.pokemonclient.state.FieldNotice? = null,
    val messageQuiet: Int = 0,
    val messageBusy: Int = 0,
    /**
     * Super Repels in the bag (item 76): X opens the start menu, BAG the bag, the item its menu, USE uses one (the
     * Repel's steps set again, a message), B goes back; just enough of the menus for the bag's own recipe.
     */
    var superRepels: Int = 0,
) : GridGame(x, y) {
    /** The Repel's steps left now ([FieldState.repelSteps]): [repelSteps], 0 once its notice was closed, 200 after a Super Repel. */
    var repelNow: Int? = repelSteps

    /** The start menu / bag screen open over the field, or null. */
    private var menu: Screen? = null
    private var previousButtons: Set<dev.kotlinds.pokemonclient.console.Button> = emptySet()

    override val repelItems get() = listOf(77, 76, 79)

    private fun superRepelBag() = ScriptedUi.bag("items", if (superRepels > 0) listOf(dev.kotlinds.pokemonclient.state.Entry("item:76", "Super Repel")) else emptyList())

    /** The menus' answer to the buttons pressed this frame; true while a menu is open (the field ignores the D-pad). */
    private fun menus(): Boolean {
        val pressed = buttons - previousButtons
        previousButtons = buttons
        val open = menu
        if (open == null) {
            if (dev.kotlinds.pokemonclient.console.Button.X in pressed && message == null && !inBattle) menu = ScriptedUi.START
            return menu != null
        }
        for (button in pressed) {
            val current = menu ?: return false
            val selectable = current as? Screen.Selectable
            val at = (selectable?.cursor as? dev.kotlinds.pokemonclient.state.Cursor.At)?.index
            val id = at?.let { selectable.entries[it].id }
            menu = when (button) {
                dev.kotlinds.pokemonclient.console.Button.A -> when {
                    ScriptedUi.isStart(current) && id == "option:bag" -> superRepelBag()
                    current is Screen.Bag && id == "item:76" -> ScriptedUi.menu("option:use", "option:give", "option:cancel", item = dev.kotlinds.pokemonclient.state.ItemId(76))
                    current is Screen.ContextMenu && id == "option:use" -> {
                        superRepels--
                        repelNow = 200
                        ScriptedUi.dialogue("ACE used the Super Repel.")
                    }
                    current is Screen.Dialogue -> superRepelBag()
                    else -> current
                }
                dev.kotlinds.pokemonclient.console.Button.B -> when (current) {
                    is Screen.Bag -> ScriptedUi.START
                    is Screen.ContextMenu, is Screen.Dialogue -> superRepelBag()
                    else -> null
                }
                else -> if (selectable == null || at == null) current
                    else selectable.topology.next(at, button)?.let { ScriptedUi.withCursor(selectable, it) } ?: current
            }
        }
        return menu != null
    }
    private var fadeLeft = 0

    /** Where the message of [messageTile] is: not started (null), its quiet and busy frames left, the box (0, 0), closed. */
    private var message: Pair<Int, Int>? = null
    private var messageClosed = false

    /** Presses of A that closed the message box. */
    var messagePresses = 0
    val area: Area = run {
        val width = rows.maxOf { it.length }
        Area(0, "test", 0, 0, width, rows.size, Array(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { '#' }) {
                '#' -> TileInfo(true, TileKind.Wall)
                '"' -> TileInfo(false, TileKind.TallGrass)
                'c' -> TileInfo(false, TileKind.Cave)
                '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
                // Floors with known heights (BDHC units): ',' low ground (height 1), '^' a raised shore (height 2), 'w' below.
                ',' -> TileInfo(false, TileKind.Floor, listOf(8))
                '^' -> TileInfo(false, TileKind.Floor, listOf(16))
                // A raised walkway (height 6), more than a step above the plain floor.
                'w' -> TileInfo(false, TileKind.Floor, listOf(48))
                else -> TileInfo(false, TileKind.Floor)
            }
        }, zones = if (landEncounters > 0) IntArray(width * rows.size) { 1 } else null)
    }
    var inBattle = false
    var spotted = false
    private var approachLeft = approachFrames

    override val name = "Walking"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = area
        override val zoneCount = 2
        override val encounterTables = this@WalkingGame.encounterTables
        override fun encounterChance(zoneId: Int, water: Boolean, conditions: dev.kotlinds.pokemonclient.world.EncounterConditions) =
            if (water || conditions.repelLevel != null) 0.0 else landEncounters * if (conditions.landMovement == MovementMode.WALK) 1.0 else 2.0
    }
    override fun fieldMoveRule(move: FieldMoveKind) = FieldMoveRule(MoveId(57), "Fog")
    override fun state(memory: Memory): GameState {
        val height = (area.tile(x, y)?.heights?.firstOrNull() ?: 0) / dev.kotlinds.pokemonclient.world.FIELD_HEIGHT_UNITS
        val field = FieldState(1, MapName(1, map = "test"), x, y, height, facing, MovementMode.WALK, moving = false, objects = people, trainerEncounter = spotted,
            engagedTrainerId = spotterId.takeIf { spotted }, autoRun = autoRun, repelSteps = repelNow)
        val battle = if (inBattle) BattleState(BattleKind.WILD, false, null, emptyList(), emptyList(), emptyList(), null) else null
        val party = if (repelSteps == null && superRepels == 0) emptyList() else listOf(lead)
        val bag = listOf(dev.kotlinds.pokemonclient.state.BagPocket("items", if (superRepels > 0) listOf(ScriptedUi.item(76, "Super Repel", superRepels)) else emptyList()))
        val shown = message
        val screen = when {
            inBattle -> Screen.Battle(Awaiting.ANIMATION)
            menu != null -> menu!!
            shown != null && shown.first > 0 -> Screen.Overworld(null, Awaiting.INPUT)
            shown != null && shown.second > 0 -> Screen.Overworld(null, Awaiting.ANIMATION)
            shown != null -> Screen.Dialogue(TextSource.FIELD, null, "A message", Awaiting.INPUT, messageNotice)
            // The "!" and the walk up: still the overworld, busy.
            fadeLeft > 0 -> Screen.Animation(AnimationKind.TRANSITION)
            spotted && approachLeft > 0 -> Screen.Overworld(null, Awaiting.ANIMATION)
            // The trainer walked up and talks (its intro text before the battle).
            spotted -> Screen.Dialogue(TextSource.FIELD, "Youngster Joey", "I just lost, so I'm trying to find more Pokémon.", Awaiting.INPUT)
            else -> Screen.Overworld(null, Awaiting.INPUT)
        }
        return GameState(0, screen, null, party, bag, battle, field.takeIf { !inBattle })
    }

    /** The party's lead with a Repel at work ([repelSteps]): level 50. */
    private val lead = dev.kotlinds.pokemonclient.state.PartyMon(
        id = dev.kotlinds.pokemonclient.state.MonId(1, 1), slot = 0, species = dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(25), "Pikachu"),
        nickname = null, level = 50, hp = 10, maxHp = 10, status = null, types = emptyList(), heldItem = null, ability = null, moves = emptyList(),
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    override fun busy(): Boolean {
        if (menus()) return true
        message?.let { (quiet, busy) ->
            when {
                quiet > 0 -> message = quiet - 1 to busy
                busy > 0 -> message = 0 to busy - 1
                dev.kotlinds.pokemonclient.console.Button.A in buttons -> {
                    messagePresses++
                    message = null
                    messageClosed = true
                    if (messageNotice == dev.kotlinds.pokemonclient.state.FieldNotice.REPEL_WORE_OFF) repelNow = 0
                    return false
                }
            }
            return true
        }
        if (fadeLeft > 0) {
            if (--fadeLeft == 0) doorTile?.let { (dx, dy) -> moveTo(dx, dy) }
            return true
        }
        sightOnTurn?.let { (tile, direction) -> if ((x to y) == tile && facing == direction) spotted = true }
        if (spotted && approachLeft > 0) approachLeft--
        return inBattle || spotted || (x to y) == frozenTile
    }

    override fun step(direction: Direction) {
        val nx = x + direction.dx
        val ny = y + direction.dy
        if ((nx to ny) == doorTile) {
            fadeLeft = FADE_FRAMES
            return
        }
        val free = area.tile(nx, ny)?.let { !it.blocked && it.kind !is TileKind.Water } == true && (nx to ny) !in invisibleWalls && people.none { it.x == nx && it.y == ny }
        if (!free) return
        moveTo(nx, ny)
        if ((x to y) == messageTile && !messageClosed) message = messageQuiet to messageBusy
        if ((x to y) == battleTile) inBattle = true
        if ((x to y) == sightTile) spotted = true
    }

    companion object {
        /** Frames of a door's fade. */
        const val FADE_FRAMES = 20
    }
}

class MovePlansTest {

    @Test
    fun goToWalksAroundWallsToTheTile() {
        val game = WalkingGame(listOf(".....", ".###.", "....."), x = 0, y = 0)
        val outcome = MovePlans.goTo.run(GameAction.GoTo(4, 2, null), game.context())
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(4 to 2, game.x to game.y)
    }

    @Test
    fun walksRunButWalkOntoTheTilesWhereWildPokemonAppear() {
        // Floor, two grass tiles, floor: run, walk the grass (running doubles the encounter roll), run again.
        val rows = listOf(".\"\"..")
        val game = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1)
        game.facing = Direction.EAST
        val before = game.console.frame
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 0, 4 to 0), game.visited.drop(1))
        assertEquals(listOf(false, false, true, true), game.ran)
        // One hold: B pressed or let go tile by tile as the step before starts, never a stop at the edge of the grass
        // (the game reads B when each step begins).
        val frames = game.console.frame - before
        assertTrue(frames < 4 * GridGame.STEP_FRAMES + 30, "took $frames frames")
        // step too, one straight line whose pace changes on the way.
        val stepped = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1)
        assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4), stepped.context()))
        assertEquals(listOf(false, false, true, true), stepped.ran)
        // Asked to run there too: B all along.
        val running = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null, MoveOptions(runInEncounterAreas = true)), running.context()))
        assertEquals(listOf(true, true, true, true), running.ran)
        // Grass where nothing appears (no encounter table) is run through; run = false walks everywhere.
        val empty = WalkingGame(rows, x = 0, y = 0)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), empty.context()))
        assertEquals(listOf(true, true, true, true), empty.ran)
        val walking = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null, MoveOptions(run = false)), walking.context()))
        assertEquals(listOf(false, false, false, false), walking.ran)
    }

    @Test
    fun walksRunOntoTheGrassARepelCoversThenWalkOnceItWearsOff() {
        // Floor, four grass tiles, floor, a Repel of 2 steps left: the first two grass tiles are covered (nothing can
        // appear: run), the next two come after it wore off (walked, like without Repel), then the floor again.
        val rows = listOf(".\"\"\"\".")
        val game = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1, repelSteps = 2)
        game.facing = Direction.EAST
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(5, 0, null), game.context()))
        assertEquals(listOf(true, true, false, false, true), game.ran)
        // step counts the same way.
        val stepped = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1, repelSteps = 2)
        assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.EAST, 5), stepped.context()))
        assertEquals(listOf(true, true, false, false, true), stepped.ran)
        // A Repel lasting the whole way: run all along; without Repel, the grass is walked.
        val lasting = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1, repelSteps = 10)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(5, 0, null), lasting.context()))
        assertEquals(listOf(true, true, true, true, true), lasting.ran)
        val none = WalkingGame(rows, x = 0, y = 0, landEncounters = 0.1)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(5, 0, null), none.context()))
        assertEquals(listOf(false, false, false, false, true), none.ran)
    }

    @Test
    fun theRouteWeighsTheGrassAsWalkedWhenTheWalkWalksThere() {
        val field = FieldState(1, MapName(1), 0, 0, 0, Direction.EAST, MovementMode.WALK, moving = false)
        assertEquals(FootPace(MovementMode.RUN, MovementMode.WALK), FootPace.of(field, MoveOptions()))
        assertEquals(FootPace(MovementMode.RUN, MovementMode.RUN), FootPace.of(field, MoveOptions(runInEncounterAreas = true)))
        // The running shoes switched on: the game holds B itself, the walk can't walk anywhere.
        assertEquals(FootPace(MovementMode.RUN, MovementMode.RUN), FootPace.of(field.copy(autoRun = true), MoveOptions(run = false)))
        // Without the running shoes, B does nothing: walking all along.
        assertEquals(FootPace(MovementMode.WALK, MovementMode.WALK), FootPace.of(field.copy(runningShoes = false), MoveOptions()))
        assertEquals(FootPace(MovementMode.BIKE, MovementMode.BIKE), FootPace.of(field.copy(movement = MovementMode.BIKE), MoveOptions()))
    }

    @Test
    fun aRefusedStepIsLearnedAndTheRouteComputedAgain() {
        // The map says (2,0) is free, the game refuses it: the walker goes around through the bottom row.
        val game = WalkingGame(listOf(".....", "....."), x = 0, y = 0, invisibleWalls = setOf(2 to 0))
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(4 to 0, game.x to game.y)
        assertTrue((2 to 0) !in game.visited)
    }

    /**
     * NOTES (map randomizer run, Whirl Islands B2F): "the game refused 6 steps on the way to warp:4" told neither where
     * nor which way. The refusals are listed, each with what the map and the RAM say of the tile.
     */
    @Test
    fun aWalkGivingUpTellsWhichStepsWereRefusedAndWhy() {
        // A corridor whose only way is refused by the game although the map allows it.
        val game = WalkingGame(listOf("....."), x = 0, y = 0, invisibleWalls = setOf(2 to 0))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        val detail = failed.error.message
        assertTrue("the game refused 1 step(s): east from 1,0 (nothing on the map explains it at 2,0" in detail, detail)
    }

    @Test
    fun aRefusedStepSaysWhatBlocked() {
        // Someone on the next tile, then a tile on another level (a raised shore: height 2 against the floor's 0).
        val follower = FieldObject("person:9", "Pikachu", FieldObjectKind.FOLLOWER, 1, 0, Direction.WEST)
        val blocked = WalkingGame(listOf("...."), x = 0, y = 0, invisibleWalls = setOf(1 to 0), people = listOf(follower.copy(kind = FieldObjectKind.PERSON, label = "boy")))
        val person = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 2), blocked.context())).error.message
        assertTrue("blocked after 0 tile(s) at 0,0: can't go east from there (person:9 (boy) stands on 1,0)" in person, person)
        val withFollower = WalkingGame(listOf("...."), x = 0, y = 0, invisibleWalls = setOf(1 to 0), people = listOf(follower))
        val pet = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 2), withFollower.context())).error.message
        assertTrue("your Pokémon following you stands on 1,0" in pet, pet)
        val cliff = WalkingGame(listOf(".www"), x = 0, y = 0, invisibleWalls = setOf(1 to 0))
        val level = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 1), cliff.context())).error.message
        assertTrue("1,0 is on another level (height 6 there, 0 here" in level, level)
    }

    @Test
    fun aWildBattleInterruptsTheWalk() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, battleTile = 2 to 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(InterruptionCause.WILD_BATTLE, assertIs<ActionError.Interrupted>(failed.error).by)
    }

    @Test
    fun aTrainerSeeingThePlayerInterruptsTheWalkWithTrainerSight() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, sightTile = 2 to 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(InterruptionCause.TRAINER_SIGHT, assertIs<ActionError.Interrupted>(failed.error).by)
    }

    @Test
    fun theStepsBeforeATrainerSeesThePlayerAreCountedFromTheRealPositions() {
        // The "!" keeps the overworld on screen while the direction is still held: the walk stops there (no refused
        // step, no new plan) and tells the tiles really walked (NOTES: "after: 0 step(s)" after surfing ten tiles).
        val game = WalkingGame(listOf(".........."), x = 0, y = 0, sightTile = 6 to 0, approachFrames = 30)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(9, 0, null), game.context()))
        val error = assertIs<ActionError.Interrupted>(failed.error)
        assertEquals(InterruptionCause.TRAINER_SIGHT, error.by)
        assertTrue(error.performed.startsWith("6 step(s)"), error.performed)
    }

    @Test
    fun interactingWithATrainerWhoSeesThePlayerOnTheWayIsTheBattleAskedFor() {
        val bert = FieldObject("person:4", "Bird Keeper Bert", FieldObjectKind.PERSON, 9, 0, Direction.WEST,
            trainer = FieldTrainer(583, "Bird Keeper", "Bert", defeated = false, sightRange = 3))
        val game = WalkingGame(listOf(".........."), x = 0, y = 0, people = listOf(bert), sightTile = 6 to 0, approachFrames = 30, spotterId = 583)
        val outcome = MovePlans.interact.run(GameAction.Interact("person:4"), game.context())
        val detail = assertIs<ActionOutcome.Done>(outcome).detail.orEmpty()
        assertTrue("the battle you asked for" in detail && "6 step(s)" in detail, detail)
    }

    /**
     * The trainer asked for may see the player only as they turn to face it (after the walk arrived): still the battle
     * asked for, and the tiles walked are told (not "0 step(s)").
     */
    @Test
    fun interactingWithATrainerWhoSeesThePlayerTurningToItIsTheBattleAskedFor() {
        // The only tile next to Joey is 0,0 (a wall below him): reached facing north, then a turn east.
        val joey = FieldObject("person:4", "Youngster Joey", FieldObjectKind.PERSON, 1, 0, Direction.WEST,
            trainer = FieldTrainer(583, "Youngster", "Joey", defeated = false, sightRange = 1))
        val game = WalkingGame(listOf("..", ".#", ".."), x = 0, y = 2, people = listOf(joey), approachFrames = 30, spotterId = 583,
            sightOnTurn = (0 to 0) to Direction.EAST)
        game.facing = Direction.NORTH
        val detail = assertIs<ActionOutcome.Done>(MovePlans.interact.run(GameAction.Interact("person:4"), game.context())).detail.orEmpty()
        assertTrue("the battle you asked for" in detail && "2 step(s)" in detail, "$detail at ${game.x},${game.y}")
    }

    /**
     * Before the step has started, any screen but the overworld stops the hold (a door's fade included): the step
     * reports the game taking over, not a plain move, and the direction isn't held into the transition.
     */
    @Test
    fun aFadeBeforeTheStepStartedStopsTheHold() {
        val game = WalkingGame(listOf("..."), x = 0, y = 0, doorTile = 1 to 0)
        game.facing = Direction.EAST
        val step = MovePlans.stepOnce(game.context(), Direction.EAST, Node(1, 0), MoveOptions())
        val stopped = assertIs<MovePlans.StepResult.Stopped>(step)
        assertIs<Screen.Animation>(stopped.state.screen)
        assertEquals(0 to 0, game.x to game.y)
    }

    /**
     * A boulder's push faces it first, verified: when the game never turns the player (three taps), the push stops with
     * the typed error instead of holding the direction blindly.
     */
    @Test
    fun aPushWhoseTurnNeverHappensIsATypedErrorNotABlindHold() {
        val game = WalkingGame(listOf("..."), x = 0, y = 0, frozenTile = 0 to 0)
        game.facing = Direction.SOUTH
        val edge = PushEdge(Node(0, 0), Direction.EAST, 1 to 0, 2 to 0, needsStrength = true)
        val failed = assertIs<MovePlans.StepResult.Failed>(FieldMoveWalk.push(game.context(), edge, MoveOptions()))
        val error = assertIs<ActionError.VerificationFailed>(failed.error)
        assertEquals("east", error.expected)
        assertEquals(3, error.attempts)
        assertEquals(0 to 0, game.x to game.y)
    }

    @Test
    fun anotherTrainerSeeingThePlayerStillInterruptsTheInteraction() {
        val bert = FieldObject("person:4", "Bird Keeper Bert", FieldObjectKind.PERSON, 9, 0, Direction.WEST,
            trainer = FieldTrainer(583, "Bird Keeper", "Bert", defeated = false, sightRange = 3))
        val game = WalkingGame(listOf(".........."), x = 0, y = 0, people = listOf(bert), sightTile = 6 to 0, approachFrames = 30, spotterId = 584)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.interact.run(GameAction.Interact("person:4"), game.context()))
        assertEquals(InterruptionCause.TRAINER_SIGHT, assertIs<ActionError.Interrupted>(failed.error).by)
    }

    @Test
    fun interactPicksATileAtThePersonsOwnHeight() {
        // The game answers A only at the person's height (sub_0203DBD4): from the low tile next to them (2,0) A says
        // nothing, from the raised one (4,0) it works, although it is farther.
        val fisherman = FieldObject("person:1", "fisherman", FieldObjectKind.PERSON, 3, 0, Direction.WEST, height = 2)
        val game = WalkingGame(listOf(",,,^^", ",,,,^"), x = 0, y = 0, people = listOf(fisherman))
        assertIs<ActionOutcome.Done>(MovePlans.interact.run(GameAction.Interact("person:1"), game.context()))
        assertEquals(4 to 0, game.x to game.y)
    }

    @Test
    fun interactStopsNextToThePersonAndFacesIt() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        val game = WalkingGame(listOf("....", "...."), x = 0, y = 1, people = listOf(nurse))
        // Nothing happens on A in this fake: facing the person, that's "nothing to say", not a failure to get there.
        val outcome = MovePlans.interact.run(GameAction.Interact("person:0"), game.context())
        assertTrue("nothing to say" in assertIs<ActionOutcome.Done>(outcome).detail.orEmpty(), outcome.toString())
        assertTrue(game.x to game.y in setOf(2 to 0, 3 to 1), "next to the person, not on it: ${game.x},${game.y}")
    }

    /**
     * The turn towards the person is checked before A (the verification rule): when the game never turns the player,
     * `interact` stops with an explicit error after three taps instead of pressing A facing elsewhere.
     */
    @Test
    fun interactNeverPressesAWhenTheTurnDidNotHappen() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        // The straight way ends on 3,1 facing east; the player must turn north there, which this game refuses.
        val game = WalkingGame(listOf("....", "...."), x = 0, y = 1, people = listOf(nurse), frozenTile = 3 to 1)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.interact.run(GameAction.Interact("person:0"), game.context()))
        val error = assertIs<ActionError.VerificationFailed>(failed.error)
        assertEquals("north", error.expected)
        assertEquals("east", error.actual)
        assertEquals(3, error.attempts)
        assertEquals(3 to 1, game.x to game.y)
    }

    @Test
    fun unknownTargetsListTheKnownOnes() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        val game = WalkingGame(listOf("...."), x = 0, y = 0, people = listOf(nurse))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "person:9"), game.context()))
        assertEquals(listOf("person:0", "frontier"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
    }

    @Test
    fun findEncounterWalksToTheGrassAndPacesUntilABattle() {
        val game = WalkingGame(listOf(".\"\""), x = 0, y = 0, battleTile = 1 to 0, landEncounters = 0.1)
        // The battle starts on the first grass tile: that's the success of this action.
        assertEquals("wild battle", assertIs<ActionOutcome.Done>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context())).detail)
    }

    /** NOTES (map randomizer run): "find_encounter doesn't work in caves (no way to tall grass)". */
    @Test
    fun findEncounterPacesOnACaveFloor() {
        // No grass at all: the cave floor (2,0) is where wild Pokémon appear; the battle starts on stepping back onto it.
        val game = WalkingGame(listOf("..c."), x = 0, y = 0, battleTile = 2 to 0, landEncounters = 0.1)
        assertEquals("wild battle", assertIs<ActionOutcome.Done>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context())).detail)
    }

    @Test
    fun findEncounterPacesOffASingleTileAndBack() {
        // One grass tile: pacing steps off it and back onto it (each entry rolls).
        val game = WalkingGame(listOf("\"."), x = 1, y = 0, landEncounters = 0.1)
        val walk = game.visited.size
        assertIs<ActionOutcome.Failed>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context()))
        assertTrue(game.visited.drop(walk).count { it == 0 to 0 } > 10, "paced onto the grass tile")
    }

    @Test
    fun findEncounterSaysWhenTheMapHasNoWildPokemon() {
        // Grass, but the map's tables have nobody for it: nothing to pace for.
        val game = WalkingGame(listOf(".\"\""), x = 0, y = 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context()))
        assertTrue("no tall grass or cave floor with wild Pokémon" in assertIs<ActionError.Unavailable>(failed.error).detail, failed.toString())
    }

    /**
     * A game whose encounter tables aren't decoded ([dev.kotlinds.pokemonclient.world.EncounterTables.UNKNOWN]): the
     * tile kinds alone tell where wild Pokémon may appear, the walk still goes to the grass and paces there.
     */
    @Test
    fun findEncounterUsesTheTileKindsWhenTheTablesAreUnknown() {
        val game = WalkingGame(listOf(".\"\""), x = 0, y = 0, battleTile = 1 to 0, encounterTables = dev.kotlinds.pokemonclient.world.EncounterTables.UNKNOWN)
        assertEquals("wild battle", assertIs<ActionOutcome.Done>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context())).detail)
    }

    @Test
    fun aStraightLineIsWalkedHoldingTheDirection() {
        // 12 tiles east: held all along (a step every STEP_FRAMES frames), with one stillness check at the end
        // instead of one per tile.
        val game = WalkingGame(listOf("............."), x = 0, y = 0)
        game.facing = Direction.EAST
        val before = game.console.frame
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(12, 0, null), game.context()))
        assertEquals(12 to 0, game.x to game.y)
        val frames = game.console.frame - before
        assertTrue(frames < 12 * GridGame.STEP_FRAMES + 30, "took $frames frames")
        assertEquals((0..12).map { it to 0 }, game.visited)
    }

    @Test
    fun aTurnStartsANewSegmentAndTheWalkEndsOnTheTarget() {
        val game = WalkingGame(listOf(".....", "....."), x = 0, y = 0)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 1, null), game.context()))
        assertEquals(4 to 1, game.x to game.y)
        assertEquals(5, game.visited.size - 1, "no tile walked twice: ${game.visited}")
    }

    @Test
    fun waterWithoutSurfSaysWhereToUseItFromAndWhatIsMissing() {
        val game = WalkingGame(listOf("..~~.."), x = 0, y = 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(5, 0, null), game.context()))
        val hint = assertIs<ActionError.Unavailable>(failed.error).hint.orEmpty()
        assertTrue("from 1,0 facing east" in hint, hint)
        assertTrue("no Pokémon" in hint, hint)
    }

    private val repelEnd = dev.kotlinds.pokemonclient.state.FieldNotice.REPEL_WORE_OFF

    private fun goTo(x: Int, onRepelEnd: RepelEnd? = null) =
        GameAction.GoTo(x, 0, null, if (onRepelEnd == null) MoveOptions() else MoveOptions(onRepelEnd = onRepelEnd))

    @Test
    fun byDefaultAWalkStopsWhereTheRepelWoreOffAndSaysWhatElseItCanDo() {
        // Nathan: like the end of a battle, the agent decides (NOTES, Codex ×8: "Interrupted by script" for it).
        val game = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4, superRepels = 1)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(goTo(4), game.context()))
        val error = assertIs<ActionError.Interrupted>(failed.error)
        assertEquals(InterruptionCause.REPEL_ENDED, error.by)
        assertEquals(2 to 0, game.x to game.y)
        assertEquals(1, game.messagePresses)
        assertEquals(1, game.superRepels)
        assertTrue("the Repel wore off at 2,0" in error.performed && "continue" in error.performed && "reapply" in error.performed && "auto" in error.performed, error.performed)
    }

    @Test
    fun onRepelEndContinueClosesTheMessageAndWalksOn() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4, superRepels = 1)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(goTo(4, RepelEnd.CONTINUE), game.context()))
        assertEquals(4 to 0, game.x to game.y)
        assertEquals(1, game.superRepels)
        assertTrue("the Repel wore off at 2,0 (message closed), walked on" in done.detail.orEmpty(), done.detail)
    }

    @Test
    fun onRepelEndReapplyUsesARepelFromTheBagThenWalksOn() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4, superRepels = 2)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(goTo(4, RepelEnd.REAPPLY), game.context()))
        assertEquals(4 to 0, game.x to game.y)
        assertEquals(1, game.superRepels)
        assertEquals(200, game.repelNow)
        assertTrue("used a Super Repel and walked on" in done.detail.orEmpty(), done.detail)
    }

    @Test
    fun onRepelEndReapplyWithNoRepelLeftStopsThere() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(goTo(4, RepelEnd.REAPPLY), game.context()))
        val error = assertIs<ActionError.Interrupted>(failed.error)
        assertEquals(InterruptionCause.REPEL_ENDED, error.by)
        assertTrue("no Repel left in the bag" in error.performed, error.performed)
        assertEquals(2 to 0, game.x to game.y)
    }

    @Test
    fun onRepelEndAutoReappliesOnlyWhenWildPokemonLieAhead() {
        // Grass ahead: a Repel used, the walk goes on.
        val grass = WalkingGame(listOf("..\"\"\""), x = 0, y = 0, messageTile = 1 to 0, messageNotice = repelEnd, messageBusy = 4, superRepels = 1, landEncounters = 0.1)
        val used = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(goTo(4, RepelEnd.AUTO), grass.context()))
        assertEquals(4 to 0, grass.x to grass.y)
        assertEquals(0, grass.superRepels)
        assertTrue("used a Super Repel" in used.detail.orEmpty(), used.detail)
        // Nothing appears on the rest of the way: walked on, the Repel kept.
        val floor = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 1 to 0, messageNotice = repelEnd, messageBusy = 4, superRepels = 1, landEncounters = 0.1)
        val kept = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(goTo(4, RepelEnd.AUTO), floor.context()))
        assertEquals(4 to 0, floor.x to floor.y)
        assertEquals(1, floor.superRepels)
        assertTrue("no wild Pokémon on the rest of the way" in kept.detail.orEmpty(), kept.detail)
    }

    @Test
    fun aScenesMessageOnTheWayStillInterruptsTheWalkWhateverOnRepelEnd() {
        // The same message without the Repel's script: a scene, never pressed through, never a Repel used.
        for (mode in RepelEnd.entries) {
            val game = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageBusy = 4, superRepels = 1)
            val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(goTo(4, mode), game.context()))
            assertEquals(InterruptionCause.SCRIPT, assertIs<ActionError.Interrupted>(failed.error).by, mode.wire)
            assertEquals(2 to 0, game.x to game.y)
            assertEquals(0, game.messagePresses)
            assertEquals(1, game.superRepels)
            val stepped = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageBusy = 4, superRepels = 1)
            val stopped = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4, MoveOptions(onRepelEnd = mode)), stepped.context()))
            assertEquals(InterruptionCause.SCRIPT, assertIs<ActionError.Interrupted>(stopped.error).by, mode.wire)
            assertEquals(0, stepped.messagePresses)
        }
    }

    @Test
    fun stepStopsWhereTheRepelWoreOffByDefaultOrWalksTheRestOfItsLine() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4), game.context()))
        assertEquals(InterruptionCause.REPEL_ENDED, assertIs<ActionError.Interrupted>(failed.error).by)
        assertEquals(2 to 0, game.x to game.y)
        val walking = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4)
        val done = assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4, MoveOptions(onRepelEnd = RepelEnd.CONTINUE)), walking.context()))
        assertEquals(4 to 0, walking.x to walking.y)
        assertEquals(1, walking.messagePresses)
        assertTrue("the Repel wore off at 2,0" in done.detail.orEmpty(), done.detail)
        val reapplying = WalkingGame(listOf("....."), x = 0, y = 0, messageTile = 2 to 0, messageNotice = repelEnd, messageBusy = 4, superRepels = 1)
        assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4, MoveOptions(onRepelEnd = RepelEnd.REAPPLY)), reapplying.context()))
        assertEquals(4 to 0, reapplying.x to reapplying.y)
        assertEquals(0, reapplying.superRepels)
    }

    @Test
    fun aSceneStartingAsTheStepIsRefusedIsAScriptNotABlockedWay() {
        // NOTES (Codex, Elm's lab): "blocked after 4 tile(s) at 4,11" while the aide talked. The field reads as free
        // for a while after the player stops (the D-pad ignored), then the scene shows: an interruption.
        val game = WalkingGame(listOf("......."), x = 0, y = 0, messageTile = 2 to 0, messageQuiet = 30, messageBusy = 20)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4), game.context()))
        assertEquals(InterruptionCause.SCRIPT, assertIs<ActionError.Interrupted>(failed.error).by)
        assertEquals(0, game.messagePresses)
        // A wall stays a wall: blocked, with where.
        val wall = WalkingGame(listOf("...#..."), x = 0, y = 0)
        val blocked = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 4), wall.context()))
        assertTrue("blocked after 2 tile(s) at 2,0" in assertIs<ActionError.Unavailable>(blocked.error).detail, blocked.error.toString())
    }

    @Test
    fun stepWalksStraightAndSaysWhereItWasBlocked() {
        val game = WalkingGame(listOf("...#."), x = 0, y = 0)
        // Facing south: the step turns east by itself and walks both tiles.
        assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.EAST, 2), game.context()))
        assertEquals(2 to 0, game.x to game.y)
        val blocked = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 2), game.context()))
        assertTrue("at 2,0" in assertIs<ActionError.Unavailable>(blocked.error).detail, blocked.error.toString())
    }
}
