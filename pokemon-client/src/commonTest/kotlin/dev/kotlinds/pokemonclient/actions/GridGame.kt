package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe

/**
 * The simulated field of the walker tests (the one grid-walking fake console): holding a direction turns the player
 * that way at once, and moves them one tile every [STEP_FRAMES] frames it stays held, like the real games (simplified).
 * The games built on it decide what a step does ([step]) and when the game ignores the D-pad ([busy]: a battle, a
 * scene, a warp's fade), and decode their own state from [x], [y], [facing].
 */
internal abstract class GridGame(var x: Int, var y: Int) : PokemonGame {
    var facing = Direction.SOUTH

    /** Every tile the player stood on, in order. */
    val visited = mutableListOf(x to y)

    /** Whether B was held (running) on each move of [visited] after the first tile. */
    val ran = mutableListOf<Boolean>()

    private var held: Set<Button> = emptySet()
    private var heldFor = 0

    override val inputProbe = InputProbe { held }

    /**
     * How the game carries out the actions ([PokemonGame.recipes]): this game's own instance of the common recipes by
     * default (never one shared with another game); a test replaces it with a subclass overriding what it checks.
     */
    override var recipes: Recipes = Recipes()

    /** The buttons held on the current frame (what [busy] sees: A closing a message box...). */
    protected val buttons: Set<Button> get() = held

    /**
     * Called on every frame before the D-pad is read: true while the game ignores it (the hold starts over). Runs the
     * game's own timers (a fade, a scene, a trainer walking up).
     */
    protected open fun busy(): Boolean = false

    /** The direction was held for [STEP_FRAMES] frames: move one tile that way, or not ([moveTo]). */
    protected abstract fun step(direction: Direction)

    /** Puts the player on ([toX], [toY]) and records it in [visited]. */
    protected fun moveTo(toX: Int, toY: Int) {
        x = toX
        y = toY
        visited += x to y
        ran += Button.B in held
    }

    val console: ConsolePort = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            held = input.buttons
            val direction = Direction.entries.firstOrNull { it.button in input.buttons }
            if (busy() || direction == null) {
                heldFor = 0
                return@repeat
            }
            facing = direction
            if (++heldFor < STEP_FRAMES) return@repeat
            heldFor = 0
            step(direction)
        }
        override fun memorySize(region: MemoryRegion) = 16
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = ByteArray(0)
        override fun loadState(state: ByteArray) = true
    }

    /** A plan context on this game, with [settings]. */
    fun context(settings: ActionSettings = ActionSettings(), onProgress: (ActionProgress) -> Unit = {}) =
        PlanContext(ActionScope(console, inputProbe, onProgress = onProgress), this, settings = settings)

    companion object {
        /** Frames a direction must be held for one tile. */
        const val STEP_FRAMES = 4
    }
}
