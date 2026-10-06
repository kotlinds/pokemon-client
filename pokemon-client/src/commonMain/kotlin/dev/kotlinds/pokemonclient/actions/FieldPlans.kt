package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.FieldMoves

import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.world.FieldMoveKind

/** Recipes of field actions reached from the start menu (save...). */
internal object FieldPlans {

    /** Saves the game: start menu → SAVE → YES (→ YES again to overwrite another save), then waits for the end. */
    val saveGame = ActionPlan<GameAction.SaveGame> { _, context ->
        PartyBagPlans.openStartMenuEntry(context, "option:save").andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (save)") { it.id == "option:yes" }
        }.andThen {
            // Then, after some text: "There is already a saved file. Is it OK to overwrite it?" (YES), the saving
            // animation and "saved the game". It ends on the start menu when it was opened with X, on the overworld
            // when SAVE was touched: both are fine.
            var overwriteAnswered = false
            var result: Step<GameState> = Step.Failed(ActionError.Timeout("the save didn't end"))
            for (round in 0 until SAVE_ROUNDS) {
                val step = context.navigator.advanceUntil(SAVE_WAITS) { it.screen is Screen.YesNo || it.isSaveEnd() }
                if (step is Step.Failed) {
                    result = step
                    break
                }
                val state = (step as Step.Done).value
                if (state.isSaveEnd()) {
                    result = step
                    break
                }
                if (overwriteAnswered) {
                    result = Step.Failed(ActionError.UnexpectedScreen("the end of the save", state.screen.kind))
                    break
                }
                overwriteAnswered = true
                val answered = context.navigator.choose(Screen.YesNo::class, "YES (overwrite)") { it.id == "option:yes" }
                if (answered is Step.Failed) {
                    result = answered
                    break
                }
            }
            result
        }.then {
            PartyBagPlans.closeToOverworld(context)
            ActionOutcome.Done("saved")
        }
    }

    /**
     * Heals the party at a Pokémon Center: talk to the nurse, answer YES to "Would you like to rest your Pokémon?",
     * then read the messages until the player can walk again.
     */
    val heal = ActionPlan<GameAction.Heal> { _, context ->
        val nurse = context.state().field?.objects?.firstOrNull { it.role == PersonRole.NURSE }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no nurse here", "go to a Pokémon Center"))
        when (val talk = MovePlans.interact.run(GameAction.Interact(nurse.id), context)) {
            is ActionOutcome.Failed -> return@ActionPlan talk
            is ActionOutcome.Done -> Unit
        }
        context.navigator.advanceUntil(HEAL_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Overworld }.andThen { state ->
            if (state.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (heal)") { it.id == "option:yes" } else Step.Done(state)
        }.andThen {
            context.navigator.advanceUntil(HEAL_WAITS) { it.screen is Screen.Overworld }
        }.then { state ->
            val hurt = state.party.filter { !it.isEgg && it.hp < it.maxHp }
            if (hurt.isEmpty()) ActionOutcome.Done("party healed")
            else ActionOutcome.Failed(ActionError.Timeout("still hurt after the nurse: ${hurt.joinToString { it.displayName }}"))
        }
    }

    private const val HEAL_WAITS = 120

    /**
     * One cast of [GameAction.Fish.rod] (used from the bag) towards the water the player faces. A is pressed on the
     * very frame something bites, never before (too early reels the line in for nothing). Ends hooked (a wild
     * battle starts) or with nothing.
     */
    val fish = ActionPlan<GameAction.Fish> { action, context ->
        val start = context.state()
        val field = start.field ?: return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", start.screen.kind))
        val facing = field.facing
        val ahead = facing?.let { context.game.world?.areaOf(field.mapId)?.tile(field.x + it.dx, field.y + it.dy)?.kind }
        if (ahead != null && !(ahead is TileKind.Water && ahead.fishable)) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NOT_FACING_WATER, "The player doesn't face water", "stand at the shore, facing the water"))
        }
        // Like any key item: with Y when the rod is registered there, else through the bag.
        val cast = PartyBagPlans.activateKeyItem(context, action.rod)
        if (cast is Step.Failed) return@ActionPlan ActionOutcome.Failed(cast.error)
        var bitten = false
        var frames = 0
        while (frames < FISH_FRAMES) {
            val state = context.state()
            if (state.battle != null) {
                // What bit: the wild Pokémon, read once the battle is ready (its data is filled as it starts).
                val ready = context.navigator.settle(maxFrames = BATTLE_START_FRAMES)
                val foe = ready.battle?.battlers?.firstOrNull { !it.ref.isPlayerSide }
                return@ActionPlan ActionOutcome.Done("hooked a wild " + (foe?.let { "${it.species.name} Lv${it.level}" } ?: "Pokémon"))
            }
            val screen = state.screen
            when {
                (screen as? Screen.PressToContinue)?.reason == ContinueReason.FISHING_BITE -> {
                    bitten = true
                    context.scope.step(1, InputFrame.of(Button.A))
                }
                // "Not even a nibble" / "It got away": read it, the cast is over.
                screen is Screen.Dialogue && screen.awaiting == Awaiting.INPUT && frames > CAST_FRAMES -> context.scope.tap(Button.A)
                screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT && frames > CAST_FRAMES ->
                    return@ActionPlan ActionOutcome.Done(if (bitten) "it got away" else "nothing bit")
                else -> context.scope.step(1)
            }
            frames++
        }
        ActionOutcome.Failed(ActionError.Timeout("the cast didn't end"))
    }

    private const val FISH_FRAMES = 60 * 30

    /** The battle's intro, up to the command menu. */
    private const val BATTLE_START_FRAMES = 900
    /** The cast animation: before it ends, the overworld still shows. */
    private const val CAST_FRAMES = 90

    /**
     * Flies to a visited town: start menu → POKéMON → the Pokémon that knows Fly (read from the party in RAM, by move
     * id) → FLY → the town on the map (touched; when it's scrolled off screen, the D-pad moves the map one press at a
     * time until it shows) → YES. Checked on the map the player lands on.
     *
     * A visited town of the other region (HGSS: Fly only reaches the region the player is in) is reached in two
     * flights when the map has a region hub the player visited ([Screen.FlyMap.regionHub], Indigo Plateau): to the
     * hub, then from there to the town. Without it: OTHER_REGION ([FlyHints.regionError]).
     */
    val fly = ActionPlan<GameAction.Fly> { action, context ->
        val startMap = context.state().field?.mapId
        val first = flyOnce(context, action.destination, startMap, hubAllowed = true)
        if (first is Step.Failed) {
            PartyBagPlans.closeToOverworld(context)
            return@ActionPlan ActionOutcome.Failed(first.error)
        }
        val (landed, viaHub) = (first as Step.Done).value
        if (viaHub == null) return@ActionPlan ActionOutcome.Done(landedDetail(landed, startMap))
        // On the hub now: the second flight, from where every region can be chosen.
        when (val second = flyOnce(context, action.destination, landed.field?.mapId, hubAllowed = false)) {
            is Step.Failed -> {
                PartyBagPlans.closeToOverworld(context)
                ActionOutcome.Failed(ActionError.BatchStepFailed(1, "fly(${action.destination})", listOf("flew to $viaHub (Fly only reaches the other region from there)"), second.error))
            }
            is Step.Done -> ActionOutcome.Done(landedDetail(second.value.first, startMap) + " (flew via $viaHub: Fly only reaches the other region from there)")
        }
    }

    private fun landedDetail(state: GameState, startMap: Int?) =
        "landed in ${state.field?.mapName}" + if (state.field?.mapId == startMap) " (the town you were in: in front of its Pokémon Center)" else ""

    /**
     * One flight from the overworld: to [destination], or to the region hub when [destination] is a visited town of
     * the other region and [hubAllowed]. Returns the state after landing and, when it flew to the hub instead, the
     * hub's name.
     */
    private fun flyOnce(context: PlanContext, destination: String, startMap: Int?, hubAllowed: Boolean): Step<Pair<GameState, String?>> {
        var hub: String? = null
        return openFlyMap(context).andThen { state ->
            flyTarget(context, destination, state, startMap, hubAllowed)
        }.andThen { target ->
            hub = target.hub
            context.scope.touch(target.touch)
            context.navigator.awaitChange(context.state().screen)
            context.navigator.advanceUntil(FLY_WAITS) { it.screen is Screen.YesNo }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (fly)") { it.id == "option:yes" }
        }.andThen { awaitLanding(context, startMap) }
            .andThen { landed -> Step.Done(landed to hub) }
    }

    /**
     * Start menu → POKéMON → a Pokémon that knows Fly → FLY, up to the fly map. The flyer is read from the party (move
     * id, never a name), able to fight first. Its menu is still checked for FLY before confirming; the next Pokémon
     * knowing Fly is tried only if the game doesn't offer it.
     */
    private fun openFlyMap(context: PlanContext): Step<GameState> {
        // The game's Fly move (its rule), never a move name.
        val fly = context.game.fieldMoveRule(FieldMoveKind.FLY)?.move
        val flyers = fly?.let { FieldMoves.knowers(context.state(), it) }.orEmpty().sortedBy { it.fainted }
        if (flyers.isEmpty()) return Step.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon in the party knows Fly"))
        return PartyBagPlans.openParty(context).andThen { state ->
            if (state.screen !is Screen.PartyGrid) return@andThen Step.Failed(ActionError.UnexpectedScreen("the party", state.screen.kind))
            for (flyer in flyers) {
                val opened = context.navigator.choose(Screen.PartyGrid::class, flyer.displayName) { it.id == flyer.id.toString() }
                if (opened is Step.Failed) return@andThen opened
                val menu = context.navigator.settle().screen as? Screen.ContextMenu
                if (menu?.entries?.any { it.id == "fieldmove:fly" } == true) {
                    return@andThen context.navigator.choose(Screen.ContextMenu::class, "FLY") { it.id == "fieldmove:fly" }
                }
                // Not offered: back to the party, then the next one.
                context.navigator.press(Button.B, menu ?: context.state().screen, maxFrames = 120)
            }
            Step.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "The game doesn't offer FLY for ${flyers.joinToString { it.displayName }}", "heal them first"))
        }.andThen { awaitFlyMap(context) }
    }

    /**
     * After FLY: the fly map, or the game's refusal ("You can't use that here", a menu message read with A, then the
     * party again): NOT_FLYABLE_HERE.
     */
    private fun awaitFlyMap(context: PlanContext): Step<GameState> {
        // A message on the way is the game's refusal: read it, then the party is back instead of the map.
        var refused = false
        val opened = context.navigator.advanceUntil(FLY_WAITS, waitOn = { it is Screen.Animation }, onMessage = { refused = true }) { it.screen is Screen.FlyMap }
        return when {
            opened is Step.Done -> opened
            (opened as Step.Failed).error is ActionError.Timeout -> Step.Failed(ActionError.Timeout("the fly map didn't open"))
            refused -> Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_FLYABLE_HERE, "The game refused Fly here", "go outdoors"))
            else -> Step.Failed(ActionError.UnexpectedScreen("the fly map", context.state().screen.kind))
        }
    }

    /**
     * Waits for the landing: the fly map closes, the flight plays, and the player can walk again — on another map, or
     * on the same one when flying to the town the player is in (it lands in front of the Pokémon Center).
     */
    private fun awaitLanding(context: PlanContext, startMap: Int?): Step<GameState> {
        var left = false
        var frames = 0
        while (frames < FLY_LANDING_FRAMES) {
            val state = context.state()
            val screen = state.screen
            when {
                screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT && (left || state.field?.mapId != startMap) -> return Step.Done(context.navigator.settle())
                screen is Screen.YesNo || screen is Screen.FlyMap -> Unit
                (screen is Screen.Dialogue || screen is Screen.PressToContinue) && screen.awaiting == Awaiting.INPUT -> {
                    left = true
                    context.scope.tap(Button.A)
                }
                else -> left = true
            }
            context.scope.step(LANDING_POLL)
            frames += LANDING_POLL
        }
        return Step.Failed(ActionError.Timeout("the flight didn't land"))
    }

    /** Where to touch on the fly map, and the hub's name when it is the region hub instead of the town asked. */
    private data class FlyTarget(val touch: dev.kotlinds.pokemonclient.console.TouchPoint, val hub: String? = null)

    /**
     * The touch point of [destination] on the fly map (`fly:<map id>` or the town's name), moving the map with the
     * D-pad when it's off screen (each press re-read). Fails for towns not visited yet. A visited town of the other
     * region gives the region hub's touch point instead when [hubAllowed] and the hub can be chosen.
     */
    private fun flyTarget(context: PlanContext, destination: String, start: GameState, startMap: Int?, hubAllowed: Boolean): Step<FlyTarget> {
        // `fly:<id>`, the town's label, or its map's name with or without "Town" / "City" ("Cerulean" = "Cerulean City").
        fun names(e: Entry): Boolean {
            val id = e.id.removePrefix("fly:").toIntOrNull() ?: return false
            return matchesRef(destination, "fly", id, e.label) || MapName.sameMapName(e.label, destination) ||
                context.game.mapName(id).let { it.isNamed(destination) || it.placeIs(destination) }
        }
        val map = start.screen as? Screen.FlyMap ?: return Step.Failed(ActionError.UnexpectedScreen("the fly map", start.screen.kind))
        val asked = map.entries.firstOrNull { e -> e.id == destination || (e.id.startsWith("fly:") && names(e)) }
            ?: return Step.Failed(FlyHints.otherRegion(context, destination, startMap) ?: ActionError.InvalidParameter("destination", destination,
                map.entries.filter { it.selectable && it.id.startsWith("fly:") }.map { "${it.id} (${it.label})" }))
        val hub = map.regionHub?.let { id -> map.entries.firstOrNull { it.id == id } }
        val entry = when {
            asked.selectable -> asked
            // Another region: by the hub when it can be chosen (visited), else the OTHER_REGION error.
            asked.id in map.otherRegion && hubAllowed && hub != null && hub.selectable && hub.id != asked.id -> hub
            else -> return Step.Failed(FlyHints.notSelectable(context, asked, map, startMap))
        }
        val viaHub = if (entry === asked) null else entry.label
        return steerTo(context, entry).andThen { Step.Done(FlyTarget(it, viaHub)) }
    }

    /**
     * The touch point of [entry] of the fly map: at once when it's on screen; else the cursor is steered towards the
     * town's cell, one press at a time (the map scrolls with it), until it can be touched. Each press is read back.
     */
    private fun steerTo(context: PlanContext, entry: Entry): Step<dev.kotlinds.pokemonclient.console.TouchPoint> {
        fun find(state: GameState) = (state.screen as? Screen.FlyMap)?.entries?.firstOrNull { it.id == entry.id }
        entry.touch?.let { return Step.Done(it) }
        repeat(MAP_STEER_PRESSES) {
            val map = context.navigator.settle().screen as? Screen.FlyMap ?: return Step.Failed(ActionError.UnexpectedScreen("the fly map", context.state().screen.kind))
            find(context.state())?.touch?.let { return Step.Done(it) }
            val from = map.cursorCell
            val to = map.cells[entry.id]
            val button = when {
                from == null || to == null -> null
                to.x < from.x -> Button.LEFT
                to.x > from.x -> Button.RIGHT
                to.y < from.y -> Button.UP
                to.y > from.y -> Button.DOWN
                else -> null
            } ?: return@repeat
            context.scope.tap(button)
            context.navigator.awaitChange(map, maxFrames = 20)
        }
        find(context.navigator.settle())?.touch?.let { return Step.Done(it) }
        return Step.Failed(ActionError.NotOnScreen(entry.label, "the fly map", emptyList()))
    }

    private const val FLY_WAITS = 40
    private const val FLY_LANDING_FRAMES = 60 * 20
    private const val LANDING_POLL = 4
    private const val MAP_STEER_PRESSES = 80

    private fun GameState.isSaveEnd() = screen is Screen.Overworld || (screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU

    private const val SAVE_ROUNDS = 2
    private const val SAVE_WAITS = 120
}
