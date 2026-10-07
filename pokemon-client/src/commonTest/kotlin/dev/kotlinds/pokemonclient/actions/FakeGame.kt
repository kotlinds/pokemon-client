package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.data.GameData
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.games.gen4.Gen4FieldMoves
import dev.kotlinds.pokemonclient.games.hgss.HgssFieldMoves
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule
import dev.kotlinds.pokemonclient.world.FieldMoves

/**
 * A scripted game for testing recipes without an emulator: the current [screen] reacts to button presses through
 * [onPress] (called once per new press, on the frame the "game" reads it, like the real games do).
 */
class FakeGame(var screen: Screen, var state: (Screen) -> GameState = { GameState(0, it, null, emptyList(), null, null, null) }) : PokemonGame {

    /** The maps, for recipes that walk (none by default). */
    override var world: dev.kotlinds.pokemonclient.world.WorldSource? = null

    /** Reaction of the scripted game to a button press. */
    var onPress: (Button, Screen) -> Screen = { _, current -> current }

    /** Reaction of the scripted game to a new touch of the bottom screen. */
    var onTouch: (TouchPoint, Screen) -> Screen = { _, current -> current }

    /** Every press the game registered, in order. */
    val presses = mutableListOf<Button>()

    /** Reaction of the scripted game to each emulated frame (frame number, current screen). */
    var onFrame: (Long, Screen) -> Screen = { _, current -> current }

    /** Where the stylus was on every frame it was down, in order. */
    val touchFrames = mutableListOf<TouchPoint>()

    /** Every new touch the game registered, in order. */
    val touches = mutableListOf<TouchPoint>()
    private var touching: TouchPoint? = null

    override val name = "Fake"

    /** The bicycle's item id (none by default). */
    override var bicycleItem: Int? = null

    /**
     * How the game carries out the actions ([PokemonGame.recipes]): this game's own instance of the common recipes by
     * default (never one shared with another game); a test replaces it with a subclass overriding what it checks.
     */
    override var recipes: Recipes = Recipes()

    /** The game's data, when a test needs some (see [StubGameData]). */
    override var data: GameData? = null
    /** The game's field move rules (none by default); [hgssFieldMoves] for the real HeartGold / SoulSilver table. */
    var fieldMoveRules: (FieldMoveKind) -> FieldMoveRule? = { null }

    override fun fieldMoveRule(move: FieldMoveKind): FieldMoveRule? = fieldMoveRules(move)

    /** The scripted state, with the access to the field moves like a real game reads it ([GameState.fieldMoves]). */
    override fun state(memory: Memory): GameState = state(screen).let { it.copy(fieldMoves = FieldMoves.access(it, fieldMoveRules)) }

    /** Held buttons as the game sees them (updated by the console every frame). */
    var held: Set<Button> = emptySet()
    override val inputProbe = InputProbe { held }

    val console = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            val pressed = input.buttons - held
            held = input.buttons
            pressed.forEach { button ->
                presses += button
                screen = onPress(button, screen)
            }
            val touch = input.touch
            if (touch != null) touchFrames += touch
            if (touch != null && touching == null) {
                touches += touch
                screen = onTouch(touch, screen)
            }
            touching = touch
            screen = onFrame(frame, screen)
        }
        override fun memorySize(region: MemoryRegion) = 16
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = ByteArray(0)
        override fun loadState(state: ByteArray) = true
    }

    fun scope() = ActionScope(console, inputProbe)
    fun context() = PlanContext(scope(), this)
}

/** HeartGold / SoulSilver's field move rules (the real table, [HgssFieldMoves]), for fakes standing in for it. */
val hgssFieldMoves: (FieldMoveKind) -> FieldMoveRule? = { Gen4FieldMoves.rule(it, HgssFieldMoves.BADGES) }

/** [state] with its field moves under [rules], as a real game reads it ([GameState.fieldMoves]). */
fun withFieldMoves(state: GameState, rules: (FieldMoveKind) -> FieldMoveRule? = hgssFieldMoves): GameState =
    state.copy(fieldMoves = FieldMoves.access(state, rules))

/**
 * The common recipes with the shared bag step ([RecipeBase.bagItem], protected) opened to a test, played on the game
 * they are given to ([PokemonGame.recipes]): [bagItemOf] refuses a context of another game.
 */
internal class BagStepRecipes : Recipes() {
    fun bagItemOf(context: PlanContext, item: ItemRef): Step<dev.kotlinds.pokemonclient.state.Entry> {
        check(context.recipes === this) { "a context of another game" }
        return bagItem(context, item)
    }
}
