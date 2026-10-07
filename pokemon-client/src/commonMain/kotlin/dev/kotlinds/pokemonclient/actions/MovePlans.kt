package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.FieldMoves

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldNotice
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import dev.kotlinds.pokemonclient.world.Route
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.NeedsMechanism
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.MechanismPlanner
import dev.kotlinds.pokemonclient.world.SwitchEdge
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.choice
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.EncounterTables
import dev.kotlinds.pokemonclient.world.FIELD_HEIGHT_UNITS
import dev.kotlinds.pokemonclient.world.TeleportLink
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldSource

/**
 * Recipes of the movement actions: routes computed on the ROM's maps ([dev.kotlinds.pokemonclient.world]) with the
 * live people on top, then walked one tile at a time, checking the position after each step.
 *
 * Walking never trusts the map blindly: a step the game refuses (an invisible wall, a person who moved in the way)
 * is remembered and the route is computed again; anything that takes the screen away from the overworld (a battle,
 * a trainer spotting the player, a phone call, a script) stops the walk with [ActionError.Interrupted].
 */
internal object MovePlans {

    /** Walks to a tile or a target, on this map or another one ([WorldTravel]: through warps, holes and map edges). */
    val goTo = ActionPlan<GameAction.GoTo> { action, context -> WorldTravel.goTo(action, context) }

    /**
     * Walks next to the target, faces it and presses A: the conversation / sign / item that follows is the result.
     *
     * People move: the target is looked up again (live position) before facing it and after a silent A. When the
     * player didn't end orthogonally next to it (or two tiles away across a counter), facing it, the walk starts
     * again (at most [INTERACT_TRIES] times). A press of A facing it that shows nothing is reported as such ("nothing
     * to say"), not as a failure to get there.
     *
     * A trainer target who sees the player on the way and walks up to them starts the very battle asked for: that's
     * a success ([engagedTarget]), not an interruption.
     */
    val interact = ActionPlan<GameAction.Interact> { action, context ->
        repeat(INTERACT_TRIES) {
            val target = resolve(context, action.target, null, null) ?: return@ActionPlan unknownTarget(context, action.target)
            val talkedTo = context.state().field?.objects?.firstOrNull { objectTargetId(it) == action.target }
            val trainerId = talkedTo?.trainer?.trainerId
            // The tiles really moved (walk and turn), for an interruption after the walk arrived (see below).
            val counter = TileCounter()
            val walked = context.navigator.watching(counter::observe) { WorldTravel.walkNextTo(context, target.copy(adjacent = true)).walk }
            if (walked is Walk.Interrupted) engagedTarget(context, action.target, trainerId, walked.state, walked.steps)?.let { return@ActionPlan it }
            if (walked is Walk.NoRoute && talkedTo?.height != null && (walked.failure == RouteFailure.Unreachable || walked.failure == RouteFailure.DifferentLevel)) {
                return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "no tile next to ${action.target} at its height (${talkedTo.height}) is reachable from here: ${walked.detail}",
                    "the game only answers A facing someone at your own height (from the water, a person on the shore above " +
                        "says nothing): find the way onto their level (a beach, stairs), then interact again"))
            }
            if (walked !is Walk.Arrived || walked.through != null) return@ActionPlan walked.toOutcome(context) { "" }
            val field = walked.field
            val live = resolve(context, action.target, null, null) ?: target
            // Its height may only be known now (its map block loaded on the way): at another height than the player,
            // A would do nothing, walk again to a tile at its height (resolve now knows it) instead of pressing.
            val area = context.game.world?.areaOf(field.mapId)
            val height = area?.let { a -> field.objects.firstOrNull { objectTargetId(it) == action.target }?.let { knownHeight(a, it) } }
            if (height != null && height != field.height) return@repeat
            val facing = facingTowards(field, live) ?: return@repeat
            // Turned and checked before A (the verification rule): A facing elsewhere would talk to someone else.
            when (val faced = context.navigator.watching(counter::observe) { FieldControl.face(context, facing, "talk to ${action.target}") }) {
                is FieldControl.Facing.Faced -> Unit
                // Stopped while turning to it: the target (a trainer) seeing the player then is the battle asked for,
                // like on the way; anything else is an interruption after the tiles walked.
                is FieldControl.Facing.Stopped -> return@ActionPlan engagedTarget(context, action.target, trainerId, faced.state, counter.tiles)
                    ?: Walk.Interrupted(faced.state, counter.tiles).toOutcome(context) { "" }
                is FieldControl.Facing.Failed -> return@ActionPlan ActionOutcome.Failed(faced.error)
            }
            val beforeState = context.navigator.settle()
            val before = beforeState.screen
            val after = pressA(context, beforeState)
            val puzzleBefore = beforeState.field?.puzzle
            val puzzleAfter = after.field?.puzzle
            if (puzzleBefore != null && puzzleAfter != null && puzzleAfter != puzzleBefore) {
                return@ActionPlan ActionOutcome.Done("the puzzle changed: ${puzzleChange(puzzleBefore, puzzleAfter)}")
            }
            if (!(after.screen is Screen.Overworld && after.screen.sameAsOverworld(before))) return@ActionPlan ActionOutcome.Done("now: ${after.screen.kind}")
            // Nothing on screen: still facing it (it has nothing to say), or it walked away (try again).
            val now = after.field ?: return@ActionPlan ActionOutcome.Done("now: ${after.screen.kind}")
            val stillThere = resolve(context, action.target, null, null)?.let { facingTowards(now, it) == now.facing } == true
            if (stillThere) return@ActionPlan ActionOutcome.Done("faced ${action.target} and pressed A: no message (nothing to say, or nothing to do there)")
        }
        ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "Couldn't get right in front of ${action.target} (it keeps moving, or only a diagonal tile is reachable)"))
    }

    /**
     * Presses A (the player already faces what to press, checked by the caller) and waits for what follows: a screen
     * change, or, for the silent switches and levers, the puzzle state changing once their script has run.
     * [beforeState]: the state just before the press. The state after.
     */
    private fun pressA(context: PlanContext, beforeState: GameState): GameState {
        context.scope.tap(Button.A)
        context.navigator.awaitChange(beforeState.screen)
        var after = context.navigator.settle()
        val puzzleBefore = beforeState.field?.puzzle
        if (puzzleBefore != null && after.screen is Screen.Overworld && after.field?.puzzle == puzzleBefore) {
            context.scope.step(SWITCH_FRAMES)
            after = context.navigator.settle()
        }
        return after
    }

    /**
     * Presses the switch of [edge] (a lever of a route planned by [MechanismPlanner]): turns to face it (checked before
     * A, the verification rule), presses A and checks the puzzle changed. [Walk.Arrived] when it did; null when it
     * didn't (nothing was there to press: the caller plans again); a [Walk.Interrupted] / [Walk.Failed] otherwise.
     */
    private fun pressSwitch(context: PlanContext, edge: SwitchEdge): Walk? {
        when (val faced = FieldControl.face(context, edge.direction, "press ${edge.target}")) {
            is FieldControl.Facing.Faced -> Unit
            is FieldControl.Facing.Stopped -> return Walk.Interrupted(faced.state)
            is FieldControl.Facing.Failed -> return Walk.Failed(faced.error)
        }
        val before = context.navigator.settle()
        val after = pressA(context, before)
        val field = after.field
        if (after.screen !is Screen.Overworld || field == null) return Walk.Interrupted(after)
        return if (field.puzzle != before.field?.puzzle) Walk.Arrived(field) else null
    }

    /**
     * The success of `interact` with a trainer ([trainerId]) who engaged the player in [state] (saw them on the way or
     * while they turned to it, after [steps] tiles): the very battle asked for. Null when it isn't that trainer.
     */
    private fun engagedTarget(context: PlanContext, target: String, trainerId: Int?, state: GameState, steps: Int): ActionOutcome? {
        if (trainerId == null || trainerId !in engagedTrainers(state)) return null
        val now = context.navigator.settle()
        return ActionOutcome.Done("$target saw you on the way (after $steps step(s)) and came to battle: the battle you asked for (now: ${now.screen.kind})")
    }

    /**
     * The trainers ([dev.kotlinds.pokemonclient.state.FieldTrainer.trainerId]) engaging the player in [state]: the one
     * who saw them (walking up), or those of the battle that started.
     */
    private fun engagedTrainers(state: GameState): Set<Int> =
        setOfNotNull(state.field?.engagedTrainerId) + state.battle?.trainerIds.orEmpty()

    /** The direction to face [target] from [field]: orthogonally next to it, or two tiles away across a counter. */
    private fun facingTowards(field: FieldState, target: Target): Direction? {
        val tx = target.x ?: return null
        val ty = target.y ?: return null
        val distance = kotlin.math.abs(tx - field.x) + kotlin.math.abs(ty - field.y)
        if (distance !in 1..2) return null
        return Direction.between(field.x, field.y, tx, ty)
    }

    /**
     * Walks a straight line of [GameAction.Step.tiles] tiles, holding the direction (the turn, when the player faces
     * elsewhere, is part of the hold: no press is lost). Checked tile by tile; a refused step says where it stopped.
     */
    val step = ActionPlan<GameAction.Step> { action, context ->
        BikeRide.mount(context, action.options)
        val state = context.state()
        val start = state.field ?: return@ActionPlan notInField(context)
        val mark = FieldControl.warpMark(context)
        val d = action.direction
        val tiles = (1..action.tiles).map { Node(start.x + d.dx * it, start.y + d.dy * it) }
        // Run, but walk onto the tiles where wild Pokémon appear (like go_to): the pace changes tile by tile, held on.
        val area = context.game.world?.areaOf(start.mapId)
        val runOnto = area?.let { runOnto(it, start, action.options, stepWeights(context, state, action.options)) } ?: RunOnto { _, _ -> action.options.run }
        var walked = WalkSegments.walk(context, WalkSegments.line(d, tiles, runOnto, taken = 0))
        // A message the game shows by itself on the way (a Repel wearing off): closed, then what the agent chose
        // ([MoveOptions.onRepelEnd]: stop there by default, or walk the rest of the line).
        val notes = mutableListOf<String>()
        var notices = 0
        while (notices++ < MAX_NOTICES) {
            val stopped = walked as? WalkSegments.Result.Stopped ?: break
            when (val notice = FieldControl.closeNotice(context, stopped.state)) {
                FieldControl.Notice.None -> break
                is FieldControl.Notice.Failed -> return@ActionPlan ActionOutcome.Failed(notice.error)
                is FieldControl.Notice.Stopped -> {
                    notes += noticeNote(notice.notice, notice.state.field)
                    walked = WalkSegments.Result.Stopped(notice.state, stopped.walked)
                }
                is FieldControl.Notice.Closed -> {
                    val here = tiles.indexOfFirst { it.x == notice.field.x && it.y == notice.field.y }
                    val left = tiles.drop(here + 1)
                    val crosses = { area == null || crossesEncounters(area, stepWeights(context, context.state(), action.options), left) }
                    when (val next = afterNotice(context, notice, action.options, crosses)) {
                        is AfterNotice.Failed -> return@ActionPlan ActionOutcome.Failed(next.error)
                        is AfterNotice.Stop -> return@ActionPlan ActionOutcome.Failed(ActionError.Interrupted(next.cause,
                            "${here + 1} tile(s)" + (notes + next.note).joinToString("") { "; $it" }))
                        is AfterNotice.WalkOn -> {
                            notes += next.note
                            walked = if (left.isEmpty()) WalkSegments.Result.Reached(notice.field)
                            else WalkSegments.walk(context, WalkSegments.line(d, left, runOnto, taken = here + 1))
                        }
                    }
                }
            }
        }
        val told = notes.joinToString("") { "; $it" }
        // Into a door, onto stairs or a hole: the walk ends there (the rest of the tiles aren't walked on the new map).
        FieldControl.awaitOutcome(context, mark)?.takeUnless { PuzzleSolving.isRide(it) }?.let { return@ActionPlan ActionOutcome.Done(through(context, it).describe(it.last.to) + told) }
        when (val walked = walked) {
            is WalkSegments.Result.Reached -> ActionOutcome.Done("walked ${action.tiles} tile(s) ${d.name.lowercase()}, now at ${walked.field.x},${walked.field.y}$told")
            is WalkSegments.Result.Elsewhere -> ActionOutcome.Done("moved to ${walked.field.x},${walked.field.y} (not a straight walk: slid, pushed, or another map)")
            is WalkSegments.Result.Refused -> {
                val done = kotlin.math.abs(walked.from.x - start.x) + kotlin.math.abs(walked.from.y - start.y)
                // Walking into a boulder (Strength used) pushes it while the player stays: that's what the step did.
                pushedAhead(context, start, walked.from.x + d.dx, walked.from.y + d.dy)?.let { return@ActionPlan ActionOutcome.Done(it) }
                // A wild battle or a scene starting as the player stopped looks like a refused step at first, and a
                // scene may take the control without leaving the field screen (Elm's aide walking up to the player in
                // his lab, NOTES: "blocked after 4 tile(s)" while she talked): blocked only when the player keeps the
                // control for a moment.
                FieldControl.takenAfterRefusal(context)?.let { taken ->
                    return@ActionPlan ActionOutcome.Failed(ActionError.Interrupted(cause(context, taken), "$done tile(s)$told"))
                }
                // Standing on a warp taken by entering it (just arrived on it): the press did nothing, say how to take it.
                val underFeet = area?.warps
                    ?.firstOrNull { it.zone == start.mapId && it.x == walked.from.x && it.y == walked.from.y && it.trigger == WarpTrigger.Enter }
                ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "blocked after $done tile(s) at ${walked.from.x},${walked.from.y}: can't go ${d.name.lowercase()} from there (${refusalCause(context, walked.from, d)})",
                    underFeet?.let { "you stand on warp:${it.id}, taken by stepping onto it (pressing on it does nothing): go_to warp:${it.id} steps off and back on" }))
            }
            is WalkSegments.Result.Stopped -> ActionOutcome.Failed(ActionError.Interrupted(cause(context, walked.state), "${walked.walked} tile(s)$told"))
        }
    }

    /**
     * When the object that stood at ([x], [y]) before [start] (a boulder) has moved since: what the step did to it
     * (the game pushed it, the player staying behind), for the agent. Null when nothing moved there.
     */
    private fun pushedAhead(context: PlanContext, start: FieldState, x: Int, y: Int): String? {
        val before = start.objects.firstOrNull { it.x == x && it.y == y && it.obstacle != null } ?: return null
        val now = context.navigator.settle().field ?: return null
        val after = now.objects.firstOrNull { it.id == before.id }
        if (after != null && after.x == x && after.y == y) return null
        return "pushed ${before.id} from $x,$y " + (after?.let { "to ${it.x},${it.y}" } ?: "(it is gone: fell through a hole)") +
            "; you stay at ${now.x},${now.y}"
    }

    /**
     * Why the game may have refused the step from [from] going [direction], from what the RAM and the map say now: who
     * stands on the next tile (the Pokémon following the player, a person), or what the map has there (a wall, a
     * surface at another height: a cliff, the void under a walkway, which the text map draws like floor). "nothing on
     * the map explains it" otherwise (an invisible wall, or someone who has moved away since).
     */
    internal fun refusalCause(context: PlanContext, from: Node, direction: Direction): String {
        val x = from.x + direction.dx
        val y = from.y + direction.dy
        val field = context.navigator.settle().field ?: return "unknown"
        field.objects.firstOrNull { it.x == x && it.y == y }?.let { o ->
            return if (o.kind == FieldObjectKind.FOLLOWER) "your Pokémon following you stands on $x,$y" else "${objectTargetId(o)} (${o.label}) stands on $x,$y"
        }
        val area = context.game.world?.areaOf(field.mapId) ?: return "unknown map"
        val tile = area.tile(x, y) ?: return "$x,$y is outside the map"
        if (tile.blocked || tile.kind == TileKind.Wall) return "$x,$y is a wall or an obstacle"
        val here = area.tile(from.x, from.y)?.heights.orEmpty()
        val height = if (field.x == from.x && field.y == from.y) field.height * FIELD_HEIGHT_UNITS else here.getOrNull(from.level) ?: here.firstOrNull()
        if (height != null && tile.heights.isNotEmpty() && tile.heights.none { kotlin.math.abs(it - height) <= RouteOptions.DEFAULT_MAX_CLIMB }) {
            return "$x,$y is on another level (height ${tile.heights.joinToString("/") { (it / FIELD_HEIGHT_UNITS).toString() }} there, " +
                "${height / FIELD_HEIGHT_UNITS} here: a cliff, or the void beside a walkway; see levels)"
        }
        return "nothing on the map explains it at $x,$y: an invisible wall, or someone who has moved away"
    }

    /** Most tiles one [GameAction.Step] walks. */
    const val MAX_STEP_TILES = 20

    /**
     * Walks to the nearest tile of this map where wild Pokémon appear ([encounterTile]: tall grass, a cave's floor, the
     * water when surfing), then back and forth there until one appears: every step onto such a tile is an encounter
     * check, so a single tile is paced from a neighbour.
     */
    val findEncounter = ActionPlan<GameAction.FindEncounter> { _, context ->
        val state = context.state()
        val start = state.field ?: return@ActionPlan notInField(context)
        val world = context.game.world ?: return@ActionPlan noMap(start)
        val area = world.areaOf(start.mapId) ?: return@ActionPlan noMap(start)
        val surfing = start.movement == MovementMode.SURF
        // Looking for wild Pokémon: running onto the encounter tiles doubles the chance of each step.
        val pacing = MoveOptions(runInEncounterAreas = true)
        val conditions = encounterConditions(state, pacing)
        val ground = if (surfing) "water" else "tall grass or cave floor"
        fun encounters(x: Int, y: Int, under: EncounterConditions = conditions) = encounterTile(world, area, start.mapId, x, y, surfing, under)
        val spot = Target(ENCOUNTER_GROUND, null, null, null, isGoal = { node -> encounters(node.x, node.y) })
        when (val walked = walkTo(context, spot, pacing)) {
            is Walk.Arrived -> if (walked.through != null) return@ActionPlan walked.toOutcome(context) { "" }
            is Walk.Interrupted -> return@ActionPlan if (walked.state.battle != null) ActionOutcome.Done("wild battle") else walked.toOutcome(context) { "" }
            is Walk.NoRoute -> {
                // A Repel keeping every wild Pokémon of the map away: the tiles are there, nothing can appear.
                val repelled = conditions.repelLevel != null && (area.zoneBounds[start.mapId] ?: return@ActionPlan walked.toOutcome(context) { "" }).let { b ->
                    (b[1]..b[3]).any { y -> (b[0]..b[2]).any { x -> encounters(x, y, conditions.copy(repelLevel = null)) } }
                }
                val detail = if (repelled) "your Repel keeps the wild Pokémon of this map away (lead level ${conditions.repelLevel}): wait for it to wear off"
                else "no $ground with wild Pokémon reachable on this map from ${start.x},${start.y}"
                return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, detail))
            }
            else -> return@ActionPlan walked.toOutcome(context) { "" }
        }
        // Pace: onto another encounter tile next to this one, or off this one and back (each entry is a check).
        repeat(MAX_PACING_STEPS) {
            val field = context.state().field ?: return@ActionPlan battleOrStop(context)
            fun free(x: Int, y: Int) = area.tile(x, y)?.let { !it.blocked && it.kind != TileKind.Wall } == true &&
                field.objects.none { it.kind != FieldObjectKind.FOLLOWER && it.x == x && it.y == y } && (surfing || area.tile(x, y)?.kind !is TileKind.Water)
            val ways = Direction.entries.filter { d -> free(field.x + d.dx, field.y + d.dy) }
                .sortedByDescending { d -> encounters(field.x + d.dx, field.y + d.dy) }
            if (ways.isEmpty()) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "no free tile next to ${field.x},${field.y} to pace to"))
            var moved = false
            for (d in ways) {
                when (val step = stepOnce(context, d, Node(field.x + d.dx, field.y + d.dy), pacing)) {
                    is StepResult.Moved -> moved = true
                    is StepResult.Stopped -> return@ActionPlan battleOrStop(context)
                    // A battle starting as the step ends looks like a refused step at first: settle before trying another way.
                    is StepResult.Refused -> context.navigator.settle()
                    is StepResult.Failed -> return@ActionPlan ActionOutcome.Failed(step.error)
                }
                if (context.state().battle != null || context.state().screen !is Screen.Overworld) return@ActionPlan battleOrStop(context)
                if (moved) break
            }
            if (!moved) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                "every step from ${field.x},${field.y} was refused (${refusalCause(context, Node(field.x, field.y), ways.first())})"))
        }
        ActionOutcome.Failed(ActionError.Timeout("no wild Pokémon after $MAX_PACING_STEPS steps on the $ground"))
    }

    /**
     * True when stepping onto ([x], [y]) of [area] can start a wild battle under [conditions]: a land encounter tile
     * ([dev.kotlinds.pokemonclient.world.TileInfo.landEncounters]: tall grass, a cave's floor) on foot, water with
     * encounters when [surfing], in a zone where the game's tables have Pokémon for it
     * ([WorldSource.encounterChance] > 0: the same chances as the routes' [StepWeights]). When the game's tables are
     * unknown ([EncounterTables.UNKNOWN]), the tile's kind alone tells.
     */
    internal fun encounterTile(world: WorldSource, area: Area, zone: Int, x: Int, y: Int, surfing: Boolean, conditions: EncounterConditions): Boolean {
        val tile = area.tile(x, y)?.takeIf { !it.blocked } ?: return false
        val kind = tile.kind
        val water = kind is TileKind.Water && kind.wildEncounters
        if (if (surfing) !water else !tile.landEncounters) return false
        return when (world.encounterTables) {
            EncounterTables.UNKNOWN -> true
            EncounterTables.DECODED -> world.encounterChance(area.zoneAt(x, y) ?: zone, water = surfing, conditions) > 0.0
        }
    }

    /** What changed between two readings of a map puzzle, by id (shutters opened / closed, levers, routes). */
    private fun puzzleChange(before: PuzzleState, after: PuzzleState): String = buildList {
        val was = before.barriers.associate { it.id to it.open }
        after.barriers.filter { was[it.id] != null && was[it.id] != it.open }.forEach { add("${it.id} ${if (it.open) "opened" else "closed"}") }
        val flipped = before.switches.associate { it.id to it.flipped }
        after.switches.filter { it.flipped != null && flipped[it.id] != it.flipped }.forEach { add("${it.id} ${if (it.flipped == true) "flipped" else "back"}") }
        val routes = before.teleports.associate { it.id to it.to }
        after.teleports.filter { routes[it.id] != it.to }.forEach { add("${it.id} now leads to ${it.to.x},${it.to.y}") }
    }.joinToString().ifEmpty { "state updated" }

    // region Targets

    /**
     * Where to go: a tile, or a person / item / warp / hole / sign / hidden item of the current map by id. With
     * [adjacent] the walk stops next to it (or across a counter), never on it.
     */
    data class Target(
        val id: String,
        val x: Int?,
        val y: Int?,
        val warp: Boolean? = null,
        val adjacent: Boolean = false,
        val isGoal: ((Node) -> Boolean)? = null,
        /** For a warp: what takes it ([WarpTrigger]: entering its tile, a press on it, nothing); null for a hole. */
        val trigger: WarpTrigger? = null,
        /**
         * Without a tile ([x] null): the tiles that end the walk and may be entered although they are on another map
         * (`exit:<direction>`: the neighbour's first tiles), for walks kept on the player's map ([Overlay.zone]).
         */
        val enter: Set<Pair<Int, Int>> = emptySet(),
        /**
         * For a map object talked to (or picked up) from next to it: the height it answers A at, when known ([isGoal]
         * then keeps only the tiles next to it at that height, [talkFrom]).
         */
        val talkHeight: Int? = null,
    )

    internal fun resolve(context: PlanContext, target: String?, x: Int?, y: Int?): Target? = resolve(context.game, context.state(), context.settings, target, x, y)

    /**
     * [target] (or the tile [x], [y]) of [state]'s map as go_to and interact walk to it, under [settings] (hidden items
     * only when revealed); also what the view's reachability ([ReachSurvey]) measures. Null when unknown.
     */
    internal fun resolve(game: PokemonGame, state: GameState, settings: ActionSettings, target: String?, x: Int?, y: Int?): Target? {
        val field = state.field
        val area = field?.let { game.world?.areaOf(it.mapId) }
        if (target == null) {
            if (x == null || y == null) return null
            // A door / stairs / exit tile: going there means going through.
            val warp = area?.warps?.firstOrNull { it.x == x && it.y == y }
            val hole = area?.triggerWarps?.any { it.x == x && it.y == y } == true
            return Target("$x,$y", x, y, warp = warp != null || hole, trigger = warp?.trigger, isGoal = warp?.let { area?.let { a -> atWarpLevel(a, x, y) } })
        }
        if (field == null || area == null) return null
        if (target == PC) {
            // Any PC tile will do: go to the closest reachable one (the nearest may be walled off from here), from the
            // side the game answers from ([PokemonGame.pcFacing]; any side when the game doesn't say).
            val (px, py) = nearestPc(area, field) ?: return null
            val goals = pcTiles(area, field).flatMap { (x, y) -> pcSides(game).map { d -> x + d.dx to y + d.dy } }.filter { (x, y) -> area.tile(x, y)?.kind != TileKind.Pc }.toSet()
            return Target(PC, px, py, adjacent = true, isGoal = { node -> (node.x to node.y) in goals })
        }
        val (kind, id) = target.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val number = id.toIntOrNull()
        return when (kind) {
            "person" -> field.objects.firstOrNull { it.id == target }?.let {
                Target(target, it.x, it.y, adjacent = true, isGoal = clearingSides(game, state, settings, area, it, talkFrom(area, it)), talkHeight = knownHeight(area, it))
            }
            // Item balls are objects of the map: `item:N` is the object `person:N` lying on the ground.
            "item" -> field.objects.firstOrNull { it.kind == FieldObjectKind.ITEM_BALL && it.id == "person:$id" }
                ?.let { Target(target, it.x, it.y, adjacent = true, isGoal = talkFrom(area, it), talkHeight = knownHeight(area, it)) }
            "warp" -> area.warps.firstOrNull { it.zone == field.mapId && it.id == number }?.let { Target(target, it.x, it.y, warp = true, trigger = it.trigger, isGoal = atWarpLevel(area, it.x, it.y)) }
            "hole" -> area.triggerWarps.firstOrNull { it.zone == field.mapId && it.trigger == number }?.let { Target(target, it.x, it.y, warp = true) }
            // `sign:N` also reaches a hidden item (older ids); `hidden_item:N` only items not picked up yet. Hidden items
            // are walkthrough knowledge: unknown targets unless the application reveals them ([ActionSettings.revealHidden]).
            "sign" -> area.signs.firstOrNull { it.zone == field.mapId && it.id == number && (it.kind == SignKind.SIGN || settings.revealHidden) }
                ?.let { Target(target, it.x, it.y, adjacent = true) }
            "hidden_item" -> area.signs.firstOrNull { it.zone == field.mapId && it.id == number && it.kind == SignKind.HIDDEN_ITEM && target !in field.pickedUp && settings.revealHidden }
                ?.let { Target(target, it.x, it.y, adjacent = true) }
            // Invisible things that answer A: known like hidden items (walkthrough), or when the game shows a cue.
            "examine" -> field.examinables.firstOrNull { it.id == target && (it.cue || settings.revealHidden) }
                ?.let { e -> Target(target, e.x, e.y, adjacent = true, isGoal = examineFrom(area, e.x, e.y)) }
            // A puzzle's teleport usable now (a cart waiting at its station, a pad): walk onto the tile that starts it,
            // the ride that follows ends the walk ([PuzzleSolving.isMechanism]).
            "cart", "teleport" -> field.puzzle?.teleports?.firstOrNull { it.id == target }?.from
                ?.minByOrNull { kotlin.math.abs(it.x - field.x) + kotlin.math.abs(it.y - field.y) }
                ?.let { Target(target, it.x, it.y) }
            else -> null
        }
    }

    /**
     * The goal of a walk to the warp on ([x], [y]) when its tile has several surfaces ([WorldLinks.warpLevel]): that
     * tile at the warp's own level only (not the bridge passing over the door), so go_to and the view's reachability
     * agree that a door under a bridge isn't reached from the deck. Null (any level) otherwise.
     */
    internal fun atWarpLevel(area: Area, x: Int, y: Int): ((Node) -> Boolean)? {
        val level = WorldLinks.warpLevel(area, x, y) ?: return null
        return { node -> node.x == x && node.y == y && node.level == level }
    }

    /**
     * The tiles to talk to map object [o] from (or pick it up): those of [goalTiles] (next to it, or across a counter)
     * at its own height. The game answers A only when the player's height equals the object's (field_control.c
     * `sub_0203DC64` → `sub_0203DBD4` compares both map objects' position Y): surfing next to a fisherman standing on
     * the shore one level up, A did nothing (NOTES: "faced person:1 and pressed A: no message", heights 1 vs 2),
     * from the shore tile next to him it worked. Null (any tile next to it) when the object's height isn't known
     * yet ([knownHeight]): `interact` checks it again once there, before pressing A.
     *
     * A tile's height is the surface of the route's level there ([dev.kotlinds.pokemonclient.world.TileInfo.heights]);
     * a tile without known heights is accepted.
     */
    private fun talkFrom(area: Area, o: dev.kotlinds.pokemonclient.state.FieldObject): ((Node) -> Boolean)? {
        val height = knownHeight(area, o) ?: return null
        val tiles = goalTiles(area, Target(o.id, o.x, o.y, adjacent = true))
        return { node -> (node.x to node.y) in tiles && atHeight(area, node, height) }
    }

    /**
     * [isGoal] (the tiles to talk to [o] from) narrowed, for a trainer who steps aside once beaten
     * ([dev.kotlinds.pokemonclient.state.PuzzleState.stepAside], the Cinnabar Gym), to the sides the player can walk
     * to from where they stand and from which that step keeps open every tile they can walk to now (the step itself
     * aside): talked to from the wrong side, the trainer steps into the only corridor. [isGoal] unchanged when the
     * trainer doesn't step aside (or did already), or when no side keeps the way open (the step can't be helped then).
     */
    private fun clearingSides(
        game: PokemonGame,
        state: GameState,
        settings: ActionSettings,
        area: Area,
        o: dev.kotlinds.pokemonclient.state.FieldObject,
        isGoal: ((Node) -> Boolean)?,
    ): ((Node) -> Boolean)? {
        val field = state.field ?: return isGoal
        val stepper = field.puzzle?.stepAside?.firstOrNull { it.person == o.id && !it.beaten } ?: return isGoal
        val overlay = overlay(area, field, emptySet(), field.activeTriggers, settings.solvePuzzles, onThisMap = true, state.eventFlags)
        val options = routeOptions(field, MoveOptions(), FieldMoves.usable(FieldMoves.of(state, game::fieldMoveRule)), StepWeights.NONE)
        val start = Pathfinder(area, overlay).nodeOf(field)
        fun reach(o: Overlay) = Pathfinder(area, o).reachable(start, options, maxCost = Int.MAX_VALUE, allowJumps = true).keys.map { it.x to it.y }.toSet() + (start.x to start.y)
        val now = reach(overlay)
        val sides = Direction.entries.mapNotNull { facing ->
            // The player stands on the side opposite [facing] and faces the trainer.
            val side = o.x - facing.dx to o.y - facing.dy
            if (side !in now) return@mapNotNull null
            val step = stepper.stepFor(facing) ?: return@mapNotNull null
            val to = o.x + step.dx to o.y + step.dy
            val moved = overlay.copy(objects = overlay.objects.map { if (it.x == o.x && it.y == o.y) it.copy(x = to.first, y = to.second) else it })
            side.takeIf { reach(moved).containsAll(now - to) }
        }.toSet()
        if (sides.isEmpty()) return isGoal
        return { node -> (node.x to node.y) in sides && (isGoal?.invoke(node) ?: true) }
    }

    /**
     * [o]'s height when it can be trusted: the game computes an object's height only once the map block it stands on
     * is loaded (live on Route 20: 0 for every trainer 30 tiles away, 1 or 2 near the player), so a reading that is
     * no surface of its own tile is "not known yet". Null when unknown.
     */
    internal fun knownHeight(area: Area, o: dev.kotlinds.pokemonclient.state.FieldObject): Int? {
        val height = o.height ?: return null
        val surfaces = area.tile(o.x, o.y)?.heights.orEmpty()
        if (surfaces.isNotEmpty() && surfaces.none { kotlin.math.abs(it - height * FIELD_HEIGHT_UNITS) < FIELD_HEIGHT_UNITS }) return null
        return height
    }

    /** True when [node]'s surface is at [height] (units of [FieldState.height]), or its height is unknown. */
    internal fun atHeight(area: Area, node: Node, height: Int): Boolean {
        val heights = area.tile(node.x, node.y)?.heights.orEmpty()
        val surface = heights.getOrNull(node.level) ?: heights.firstOrNull() ?: return true
        return kotlin.math.abs(surface - height * FIELD_HEIGHT_UNITS) < FIELD_HEIGHT_UNITS
    }

    /**
     * The tiles to examine the invisible object at ([x], [y]) from: orthogonally next to it, on land (live, A from the
     * water at (5,10) facing the Cerulean Gym's Machine Part did nothing; from (4,9) on the edge it worked).
     */
    private fun examineFrom(area: Area, x: Int, y: Int): (Node) -> Boolean = { node ->
        kotlin.math.abs(node.x - x) + kotlin.math.abs(node.y - y) == 1 && area.tile(node.x, node.y)?.kind !is TileKind.Water
    }

    /** The closest PC tile around the player (Pokémon Centers have one; the player's room too). */
    private fun nearestPc(area: Area, field: FieldState): Pair<Int, Int>? =
        pcTiles(area, field).minByOrNull { (x, y) -> kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y) }

    /** The directions from a PC to the tiles it is used from: opposite the way the player faces it ([PokemonGame.pcFacing]), or every side. */
    private fun pcSides(game: PokemonGame): List<Direction> = game.pcFacing?.let { listOf(it.opposite) } ?: Direction.entries

    /** Every PC tile around the player. */
    private fun pcTiles(area: Area, field: FieldState): List<Pair<Int, Int>> =
        (-PC_SEARCH..PC_SEARCH).flatMap { dy -> (-PC_SEARCH..PC_SEARCH).map { dx -> field.x + dx to field.y + dy } }
            .filter { (x, y) -> area.tile(x, y)?.kind == TileKind.Pc }

    internal fun unknownTarget(context: PlanContext, target: String?, extra: List<String> = emptyList()): ActionOutcome {
        val field = context.state().field ?: return notInField(context)
        val known = buildList {
            field.objects.filter { it.kind != FieldObjectKind.FOLLOWER }.forEach { add(objectTargetId(it)) }
            context.game.world?.areaOf(field.mapId)?.let { area ->
                area.warps.filter { it.zone == field.mapId }.forEach { add("warp:${it.id}") }
                area.triggerWarps.filter { it.zone == field.mapId }.forEach { add("hole:${it.trigger}") }
                area.signs.filter { it.zone == field.mapId }.forEach { s ->
                    if (s.kind == SignKind.SIGN) add("sign:${s.id}")
                    else if ("hidden_item:${s.id}" !in field.pickedUp && context.settings.revealHidden) add("hidden_item:${s.id}")
                }
                field.examinables.filter { it.cue || context.settings.revealHidden }.forEach { add(it.id) }
                field.puzzle?.teleports.orEmpty().filter { it.id.startsWith("cart:") || it.id.startsWith("teleport:") }.forEach { add(it.id) }
            }
            addAll(extra)
        }
        return ActionOutcome.Failed(ActionError.InvalidParameter("target", target ?: "none", known))
    }

    /**
     * The refusal of the tile ([x], [y]) of [field]'s map as a destination when nobody can stand on it, before any
     * step: a wall or a blocked tile (not a door: going there goes through), a counter, a PC, lava, a ledge (jumped
     * over), or a tile someone or something stands on. It names what is there and what stands next to it, with the
     * target that walks next to it: an agent aiming at the wall behind a person (5,12 above Lambda's 5,13) read "not
     * connected" and thought him out of reach for 33 minutes (race notes, Radio Tower 5F). Null for a tile that can be
     * stood on (whether a way leads there is the walk's business), or when the map isn't known.
     */
    internal fun obstacleTarget(game: PokemonGame, field: FieldState, settings: ActionSettings, x: Int, y: Int): ActionError.Unavailable? {
        val area = game.world?.areaOf(field.mapId) ?: return null
        val tile = area.tile(x, y) ?: return null
        // A warp's tile (a door has collision) or a hole: going there takes it.
        if (area.warps.any { it.x == x && it.y == y } || area.triggerWarps.any { it.x == x && it.y == y }) return null
        fun objectOn(tx: Int, ty: Int) = field.objects.firstOrNull { it.kind != FieldObjectKind.FOLLOWER && it.x == tx && it.y == ty }
        fun signOn(tx: Int, ty: Int) = area.signs.firstOrNull { s -> s.zone == field.mapId && s.x == tx && s.y == ty && (s.kind == SignKind.SIGN || settings.revealHidden) }
        fun examinableOn(tx: Int, ty: Int) = field.examinables.firstOrNull { e -> e.x == tx && e.y == ty && (e.cue || settings.revealHidden) }
        /** What answers A on (tx, ty), as `id (label) at x,y`, with the id go_to and interact take. */
        fun thing(tx: Int, ty: Int): Pair<String, String>? {
            objectOn(tx, ty)?.let { o -> val id = objectTargetId(o); return id to "$id (${o.label}) at $tx,$ty" }
            signOn(tx, ty)?.let { s -> val id = (if (s.kind == SignKind.SIGN) "sign:" else "hidden_item:") + s.id; return id to "$id at $tx,$ty" }
            examinableOn(tx, ty)?.let { e -> return e.id to "${e.id} at $tx,$ty" }
            return null
        }
        val on = thing(x, y)
        val what = when {
            on != null -> "taken: ${on.second} is there"
            tile.kind is TileKind.Ledge -> "a ledge (jumped over, never stood on)"
            tile.kind == TileKind.Counter -> "a counter (talk across it)"
            tile.kind == TileKind.Pc -> "a PC (" + (game.pcFacing?.let { "use it from the tile ${it.opposite.name.lowercase()} of it: " } ?: "") + "interact pc)"
            tile.kind == TileKind.Lava -> "lava"
            tile.blocked || tile.kind == TileKind.Wall -> "a wall or an obstacle"
            else -> return null
        }
        val near = listOfNotNull(on) + Direction.entries.mapNotNull { d -> thing(x + d.dx, y + d.dy) }.filter { it != on }
        val hint = if (near.isEmpty()) "pick a tile you can stand on (see the map: '.' floor)"
        else near.distinctBy { it.first }.joinToString("; ") { (id, said) -> "$said: go_to $id walks next to it (interact $id talks to it)" }
        return ActionError.Unavailable(UnavailableReason.TARGET_IS_OBSTACLE, "$x,$y is $what: nobody can stand there", hint)
    }

    /** The target id of a map object: `item:N` for an item ball, its own id (`person:N`) otherwise. */
    fun objectTargetId(o: dev.kotlinds.pokemonclient.state.FieldObject): String =
        if (o.kind == FieldObjectKind.ITEM_BALL) "item:" + o.id.substringAfter(':') else o.id

    // endregion

    // region Walking

    /** How a walk ended. */
    sealed interface Walk {
        data class Arrived(
            val field: FieldState,
            /** What the walk did besides walking (field moves used, objects pushed), for the agent. */
            val notes: List<String> = emptyList(),
            /** The warp that ended the walk (a walk stops at the first one), or null when it ended by walking. */
            val through: Through? = null,
        ) : Walk
        /**
         * Stopped by the game; [at] is the tile the player was stepping onto, when known. [steps]: tiles really moved
         * before (counted from the positions the walk read, [TileCounter]), filled in by [walkTo] and by the trip.
         */
        data class Interrupted(
            val state: GameState,
            val steps: Int = 0,
            val at: Pair<Int, Int>? = null,
            val notes: List<String> = emptyList(),
            /** Why, when the walk itself decided to stop ([InterruptionCause.REPEL_ENDED]); null: read from [state]. */
            val cause: InterruptionCause? = null,
        ) : Walk
        data class NoRoute(
            val failure: RouteFailure,
            val detail: String,
            /** What the party can do with each field move (to say what's missing when one is needed). */
            val access: Map<FieldMoveKind, FieldMoveAccess> = emptyMap(),
            /**
             * The steps the game refused on the way before no route was left ([refusalCause] of each): the map alone
             * doesn't know them, so a diagnosis from the map (another floor, ledges) must not replace this one.
             */
            val refusals: List<String> = emptyList(),
        ) : Walk
        data class Stuck(val detail: String) : Walk

        /** A field move on the way didn't work (no question, another screen): [error] says what happened. */
        data class Failed(val error: ActionError) : Walk
    }

    /**
     * Computes a route to [target] and walks it, computing it again after each refused step (at most a few times).
     *
     * The tiles walked before an interruption are counted from the positions read along the way ([TileCounter]), not
     * from the plan: a segment cut short (a battle as the player stops, a trainer's "!" while the direction is still
     * held, a refused step and a new plan) still moved the player, and surfing tiles count like walked ones (NOTES:
     * "Interrupted by trainer sight after: 0 step(s)" after surfing ten tiles towards Bird Keeper Bert).
     */
    fun walkTo(context: PlanContext, target: Target, options: MoveOptions): Walk {
        val notes = mutableListOf<String>()
        val counter = TileCounter()
        var walk = context.navigator.watching(counter::observe) { walkTo(context, target, options, notes) }
        // A message the game shows by itself on the way (a Repel wearing off), never a scene: closed, then what the
        // agent chose ([MoveOptions.onRepelEnd]: stop there by default, or walk on, with a new Repel or not). NOTES:
        // "Interrupted by script" ×8 for "REPEL's effect wore off...".
        var notices = 0
        while (walk is Walk.Interrupted && walk.cause == null && notices++ < MAX_NOTICES) {
            when (val notice = FieldControl.closeNotice(context, walk.state)) {
                FieldControl.Notice.None -> break
                is FieldControl.Notice.Failed -> walk = Walk.Failed(notice.error)
                is FieldControl.Notice.Stopped -> {
                    notes += noticeNote(notice.notice, notice.state.field)
                    walk = Walk.Interrupted(notice.state)
                }
                is FieldControl.Notice.Closed -> when (val next = afterNotice(context, notice, options) { remainingCrossesEncounters(context, target, options) }) {
                    is AfterNotice.WalkOn -> {
                        notes += next.note
                        walk = context.navigator.watching(counter::observe) { walkTo(context, target, options, notes) }
                    }
                    is AfterNotice.Stop -> {
                        notes += next.note
                        walk = Walk.Interrupted(context.state(), cause = next.cause)
                    }
                    is AfterNotice.Failed -> walk = Walk.Failed(next.error)
                }
            }
        }
        return when (walk) {
            is Walk.Arrived -> if (notes.isEmpty()) walk else walk.copy(notes = notes)
            is Walk.Interrupted -> walk.copy(steps = counter.tiles, notes = notes)
            else -> walk
        }
    }

    /** What a walk tells of a [FieldNotice] it closed on the way ([field]: where the player stood then). */
    internal fun noticeNote(notice: FieldNotice, field: FieldState?): String = when (notice) {
        FieldNotice.REPEL_WORE_OFF -> "the Repel wore off" + (field?.let { " at ${it.x},${it.y}" } ?: "") + " (message closed)"
    }

    /** What a walk does once it closed a [FieldNotice] ([afterNotice]). */
    internal sealed interface AfterNotice {
        /** Walk on; [note] says what was done (for the answer). */
        data class WalkOn(val note: String) : AfterNotice

        /** Stop there, interrupted by [cause]; [note] says where and what the agent can do. */
        data class Stop(val note: String, val cause: InterruptionCause) : AfterNotice

        /** Something done for it failed (the Repel's use): [error]. */
        data class Failed(val error: ActionError) : AfterNotice
    }

    /**
     * After [closed] (the message closed, the player back in control): what the agent chose ([MoveOptions.onRepelEnd]).
     * [crossesEncounters]: whether the rest of the way still crosses tiles where wild Pokémon appear (only asked by
     * [RepelEnd.AUTO], it routes).
     */
    internal fun afterNotice(context: PlanContext, closed: FieldControl.Notice.Closed, options: MoveOptions, crossesEncounters: () -> Boolean): AfterNotice {
        val at = noticeNote(closed.notice, closed.field)
        return when (closed.notice) {
            FieldNotice.REPEL_WORE_OFF -> when (options.onRepelEnd) {
                RepelEnd.STOP -> AfterNotice.Stop(at + REPEL_STOP_TAIL, InterruptionCause.REPEL_ENDED)
                RepelEnd.CONTINUE -> AfterNotice.WalkOn("$at, walked on")
                RepelEnd.REAPPLY -> reapplyRepel(context, at)
                RepelEnd.AUTO -> when {
                    repelInBag(context) == null -> AfterNotice.WalkOn("$at, walked on (on_repel_end auto: no Repel left in the bag)")
                    !crossesEncounters() -> AfterNotice.WalkOn("$at, walked on (on_repel_end auto: no wild Pokémon on the rest of the way)")
                    else -> reapplyRepel(context, at)
                }
            }
        }
    }

    /** How an answer stopped by the end of a Repel goes on: what the agent can do, and the opt-in values. */
    private const val REPEL_STOP_TAIL = ": stopped there (on_repel_end stop, the default). Use a Repel and do the same action again " +
        "to go on, or let walks decide by themselves: on_repel_end continue (walk on), reapply (use one from the bag and walk on) or auto"

    /** The first Repel the bag holds, longest-lasting first ([dev.kotlinds.pokemonclient.PokemonGame.repelItems]), or null. */
    private fun repelInBag(context: PlanContext): dev.kotlinds.pokemonclient.state.BagItem? {
        val stacks = context.state().bag.orEmpty().flatMap { it.items }.filter { it.quantity > 0 }
        return context.game.repelItems.firstNotNullOfOrNull { id -> stacks.firstOrNull { it.item.id.value == id } }
    }

    /**
     * Uses a Repel from the bag ([repelInBag]) through the bag's own recipe (every press checked), and checks it took
     * when the game tells the Repel's steps. None left: stop like [RepelEnd.STOP], saying so.
     */
    private fun reapplyRepel(context: PlanContext, at: String): AfterNotice {
        val repel = repelInBag(context) ?: return AfterNotice.Stop("$at; no Repel left in the bag$REPEL_STOP_TAIL", InterruptionCause.REPEL_ENDED)
        val used = context.run(GameAction.UseItem(ItemRef("item:${repel.item.id.value}")))
        if (used is ActionOutcome.Failed) return AfterNotice.Failed(used.error)
        if (context.navigator.settle().field?.repelSteps == 0) {
            return AfterNotice.Failed(ActionError.Timeout("used ${repel.item.name}, but no Repel is at work: call get_state"))
        }
        return AfterNotice.WalkOn("$at, used a ${repel.item.name} and walked on")
    }

    /**
     * Whether the way from here to [target] (on this map) crosses tiles where wild Pokémon appear now (no Repel at
     * work): [RepelEnd.AUTO]'s question. Unknown (no map, no route found): true, a Repel used for nothing is the lesser harm.
     */
    private fun remainingCrossesEncounters(context: PlanContext, target: Target, options: MoveOptions): Boolean {
        val state = context.navigator.settle()
        val field = state.field ?: return true
        val area = context.game.world?.areaOf(field.mapId) ?: return true
        val weights = stepWeights(context, state, options)
        val pathfinder = Pathfinder(area, overlay(context, field, emptySet()))
        val goalTiles = goalTiles(area, target)
        val isGoal: (Node) -> Boolean = target.isGoal ?: { node -> (node.x to node.y) in goalTiles }
        val access = FieldMoveWalk.access(context, state)
        val route = (pathfinder.route(pathfinder.nodeOf(field), routeOptions(field, options, FieldMoves.usable(access), weights),
            if (target.adjacent) emptySet() else goalTiles, beside(area, target), isGoal) as? Pathfinder.Result.Found)?.route ?: return true
        return crossesEncounters(area, weights, route.edges.map { it.to })
    }

    /** Whether one of [tiles] of [area] is a tile where wild Pokémon appear under [weights]. */
    private fun crossesEncounters(area: Area, weights: StepWeights, tiles: List<Node>): Boolean =
        tiles.any { node -> area.tile(node.x, node.y)?.let { weights.encounter(it, area.zoneAt(node.x, node.y)) > 0 } == true }

    /** [FieldNotice]s a single walk closes at most before giving up the walk (one per Repel used: never more than a couple). */
    private const val MAX_NOTICES = 3

    private fun walkTo(context: PlanContext, target: Target, options: MoveOptions, notes: MutableList<String>): Walk {
        val refused = mutableSetOf<Pair<Node, Direction>>()
        // Each refused step, with what may have refused it ([refusalCause]): told when the walk gives up.
        val refusals = mutableListOf<String>()
        fun refuse(from: Node, direction: Direction) {
            val cause = refusalCause(context, from, direction)
            refusals += "${direction.name.lowercase()} from ${from.x},${from.y} ($cause)"
            if (!objectAt(context, from.x + direction.dx, from.y + direction.dy)) refused += from to direction
        }
        // Every move below ends with the warp rule ([FieldControl.awaitOutcome]): the walk stops at the first warp.
        var mark = FieldControl.warpMark(context)
        fun warped(): Walk.Arrived? {
            val warped = FieldControl.awaitOutcome(context, mark) ?: return null
            // A ride of the map's puzzle (a pad, a cart, a platform) is part of the way, not a warp: the walk goes on
            // (or arrives, when the mechanism was the destination) and the watch starts again from there.
            if (PuzzleSolving.isRide(warped)) {
                mark = FieldControl.warpMark(context)
                return null
            }
            return arrivedThrough(context, warped, notes)
        }
        // A warp nothing takes (only the other side's arrival): say so rather than walk there and wait.
        if (target.warp == true && target.trigger == WarpTrigger.Never) return Walk.Failed(neverTaken(target))
        var offTheBike = false
        repeat(MAX_REPLANS) {
            val state = context.navigator.settle()
            // A warp the last move ended with (every move below waited for it already): stop there.
            if (context.navigator.warps.since(mark) != null) warped()?.let { return it }
            val field = state.field ?: return Walk.Interrupted(state)
            if (state.screen !is Screen.Overworld) return Walk.Interrupted(state)
            val area = context.game.world?.areaOf(field.mapId) ?: return Walk.NoRoute(RouteFailure.StartUnknown, noMapDetail(field))
            val pathfinder = Pathfinder(area, overlay(context, field, refused))
            val start = pathfinder.nodeOf(field)
            val goalTiles = goalTiles(area, target)
            val isGoal: (Node) -> Boolean = target.isGoal ?: { node -> (node.x to node.y) in goalTiles }
            if (isGoal(start) && target.warp != true) return Walk.Arrived(field)
            // On the targeted warp already (just came in through it): what the game needs to take it from there.
            if (target.warp == true && target.trigger != null && field.x == target.x && field.y == target.y) {
                return takeWarpHere(context, target, target.trigger, field, options, mark, notes)
            }
            val access = FieldMoveWalk.access(context, state)
            val weights = stepWeights(context, state, options)
            val routeOptions = routeOptions(field, options, FieldMoves.usable(access), weights)
            // B held onto each tile as the route was planned: run, but walk where wild Pokémon appear.
            val runOnto = runOnto(area, field, options, weights)
            // A tile target may be entered whatever it is (a warp, a scene trigger); targets reached with A never are.
            val enterable = if (target.adjacent) emptySet() else goalTiles
            // Movement puzzles with a state (Blackthorn Gym platforms, Azalea Gym carts and levers): plan the rides and
            // the presses; a plain route would never step on a trigger knowingly. Movement puzzles left to the agent
            // ([ActionSettings.solvePuzzles] off): only walk, and say what to operate.
            val solve = context.settings.solvePuzzles
            val ridden = if (!solve) null else field.puzzle?.mechanics?.let { MechanismPlanner(area, overlay(context, field, refused), it).route(start, routeOptions, enterable, isGoal) }
            val route = ridden ?: when (val result = pathfinder.route(start, routeOptions, enterable, beside(area, target), isGoal)) {
                is Pathfinder.Result.Found -> result.route
                // No plain route: maybe one moving boulders / ice blocks out of the way.
                is Pathfinder.Result.Failed -> (if (solve) pushRoute(area, overlay(context, field, refused), start, routeOptions, enterable, isGoal) else null)
                    ?: run {
                        val failure = (if (solve) null else PuzzleSolving.diagnose(area, field, overlay(context, field, refused, solve = true), start, routeOptions, enterable, isGoal))
                            ?: result.failure
                        // What the way needs, in the words of the view's reachability (the same diagnosis: ReachSurvey).
                        val needs = Reachability.of(failure, result.blockers, field)?.suffix().orEmpty()
                        return Walk.NoRoute(
                            failure,
                            "no way to ${target.id} from ${field.x},${field.y}" + (if (solve) "" else " by walking only") +
                                (if (refusals.isEmpty()) "" else " once the game refused ${refusals.size} step(s): " + refusals.joinToString("; ")) + needs +
                                boulderNotes(field, result.blockers),
                            access, refusals.toList(),
                        )
                    }
            }
            // The bicycle doesn't steer on ice: off it before a way that slides (once), then planned again on foot.
            if (field.movement == MovementMode.BIKE && !offTheBike && BikeRide.crossesIce(area, route)) {
                offTheBike = true
                notes += BikeRide.getOff(context)
                return@repeat
            }
            avoidanceNotes(route, options).forEach { if (it !in notes) notes += it }
            // The steps the game counts before each move: where a Repel wearing off still covers the walk (runOnto).
            val stepsBefore = route.stepsBefore
            val scenes = sceneTiles(area, field, overlay(context, field, refused), if (target.warp == true) goalTiles else emptySet())
            var from = start
            var index = -1
            var strengthActive = false
            while (++index < route.edges.size) {
                val edge = route.edges[index]
                // Straight runs of plain steps: walked holding the direction (smooth), checked on every tile.
                val segment = WalkSegments.segmentAt(route.edges, index, area, runOnto, stepsBefore[index])
                if (segment != null) {
                    val walked = WalkSegments.walk(context, segment, scenes)
                    warped()?.let { return it }
                    when (walked) {
                        is WalkSegments.Result.Reached -> {
                            from = segment.tiles.last()
                            index += segment.tiles.size - 1
                            continue
                        }
                        is WalkSegments.Result.Elsewhere -> {
                            if (carriedFromGoal(field, target, goalTiles, segment.tiles)) return Walk.Arrived(rideEnd(context, walked.field))
                            return@repeat
                        }
                        is WalkSegments.Result.Refused -> {
                            val stuck = walked.from
                            refuse(route.edges.map { it.to }.firstOrNull { it.x == stuck.x && it.y == stuck.y } ?: start, segment.direction)
                            return@repeat
                        }
                        // [at]: the tile the player was stepping onto (a scene trigger there is what started).
                        is WalkSegments.Result.Stopped -> {
                            val at = walked.at?.let { it.x to it.y }
                            return Walk.Interrupted(if (at != null && at in scenes) sceneStarted(context) else walked.state, at = at)
                        }
                    }
                }
                if (edge is FieldMoveEdge) {
                    val used = FieldMoveWalk.use(context, edge, options)
                    warped()?.let { return it }
                    when (used) {
                        is FieldMoveWalk.Use.Done -> {
                            with(FieldMoveWalk) { notes += "used ${edge.move.label()} at ${from.x},${from.y}" }
                            if (used.field.x != edge.to.x || used.field.y != edge.to.y) return@repeat
                            from = edge.to
                            continue
                        }
                        is FieldMoveWalk.Use.Stopped -> return Walk.Interrupted(used.state)
                        is FieldMoveWalk.Use.Failed -> return Walk.Failed(used.error)
                    }
                }
                if (edge is PushEdge && edge.needsStrength && !strengthActive) {
                    when (val activated = FieldMoveWalk.activateStrength(context, edge.direction)) {
                        is FieldMoveWalk.Use.Done -> strengthActive = true
                        is FieldMoveWalk.Use.Stopped -> return Walk.Interrupted(activated.state)
                        is FieldMoveWalk.Use.Failed -> return Walk.Failed(activated.error)
                    }
                }
                if (edge is SwitchEdge) {
                    when (val pressed = pressSwitch(context, edge)) {
                        is Walk.Arrived -> {
                            notes += "pressed ${edge.target}"
                            from = edge.to
                            continue
                        }
                        // The puzzle didn't change: plan again from the state read now (at most a few times).
                        null -> {
                            refused += from to edge.direction
                            return@repeat
                        }
                        else -> return pressed
                    }
                }
                if (edge is Edge.Teleport) {
                    when (val ride = teleport(context, edge, options)) {
                        is StepResult.Moved -> {
                            // A ride to another map is a warp like any other: the walk stops there.
                            if (ride.field.mapId != field.mapId) warped()?.let { return it }
                            // A planned teleport on this map (a pad, a cart, a lift) is part of the way, not a warp
                            // to stop at: the watch starts again from where it left the player.
                            mark = FieldControl.warpMark(context)
                            // Not where the teleport should have led (it didn't fire, or went elsewhere): compute again.
                            if (ride.field.x != edge.to.x || ride.field.y != edge.to.y) return@repeat
                            from = edge.to
                            continue
                        }
                        is StepResult.Refused -> {
                            refuse(from, edge.direction)
                            return@repeat
                        }
                        is StepResult.Stopped -> return Walk.Interrupted(ride.state)
                        is StepResult.Failed -> return Walk.Failed(ride.error)
                    }
                }
                val intoWarp = area.warps.any { it.x == edge.to.x && it.y == edge.to.y }
                val slide = (edge as? Edge.Slide)?.tiles?.size ?: (edge as? PushEdge)?.takeIf { !it.needsStrength }?.tiles?.size ?: 0
                val long = edge is Edge.Jump || intoWarp || (edge as? PushEdge)?.needsStrength == true
                val step = if (edge is PushEdge && edge.needsStrength) FieldMoveWalk.push(context, edge, options)
                else stepOnce(context, edge.direction, edge.to, options, long = long, slide = slide, run = runOnto.runs(edge.to, stepsBefore[index]))
                // Entering a door, stairs or a hole (or any warp on the way): the walk is over there.
                warped()?.let { return it }
                when (step) {
                    is StepResult.Moved -> {
                        if (edge is PushEdge && step.field.x == edge.to.x && step.field.y == edge.to.y) {
                            notes += "pushed the ${if (edge.needsStrength) "boulder" else "ice block"} at ${edge.objectFrom.first},${edge.objectFrom.second} to ${edge.objectTo.first},${edge.objectTo.second}"
                        }
                        // On a scene trigger: its script runs now (and may move the player back), the walk stops there.
                        if ((step.field.x to step.field.y) in scenes && step.field.x == edge.to.x && step.field.y == edge.to.y) {
                            return Walk.Interrupted(sceneStarted(context), at = edge.to.x to edge.to.y)
                        }
                        if ((step.field.x != edge.to.x || step.field.y != edge.to.y) && carriedFromGoal(field, target, goalTiles, listOf(edge.to))) return Walk.Arrived(rideEnd(context, step.field))
                        // Slid, pushed or overshot elsewhere than planned: compute the route again from where the
                        // player really is.
                        if (step.field.x != edge.to.x || step.field.y != edge.to.y) return@repeat
                        from = edge.to
                    }
                    is StepResult.Refused -> {
                        refuse(from, edge.direction)
                        return@repeat
                    }
                    is StepResult.Stopped -> return Walk.Interrupted(step.state, at = edge.to.x to edge.to.y)
                    is StepResult.Failed -> return Walk.Failed(step.error)
                }
            }
            val end = context.navigator.settle()
            if (context.navigator.warps.since(mark) != null) warped()?.let { return it }
            val endField = end.field ?: return Walk.Interrupted(end)
            if (target.warp != true) {
                val carried = route.end?.let { carriedFromGoal(field, target, goalTiles, listOf(it)) } == true
                if (!isGoal(pathfinder.nodeOf(endField)) && !carried) return@repeat
                return Walk.Arrived(if (carried) rideEnd(context, endField) else endField)
            }
            // On the targeted warp and still on this map: an exit mat waits for its press; a door or an entrance that
            // didn't fire on entering (or anything else) is taken the way the game needs from here.
            if (target.trigger != null && endField.x == target.x && endField.y == target.y) {
                return takeWarpHere(context, target, target.trigger, endField, options, mark, notes)
            }
        }
        val refusedSteps = "the game refused ${refusals.size} step(s) on the way" + (if (refusals.isEmpty()) "" else ": " + refusals.joinToString("; "))
        return Walk.Stuck(if (target.warp == true) "couldn't take ${target.id}: no warp took the player anywhere, and $refusedSteps"
        else "$refusedSteps to ${target.id}")
    }

    /**
     * Takes the warp [target] the player stands on (arrived on it, or walked onto it without it firing), the way its
     * [trigger] asks (the game's rules, [WarpTrigger]):
     * - an exit mat, side stairs or a ladder: the press towards its direction;
     * - a door, a north entrance, a warp panel: they fire on entering the tile, pressing on them does nothing (the
     *   "TIMEOUT: the warp didn't take the player anywhere" of cave exits taken going north): step off onto a free
     *   tile next to it, then back on, the step back facing the warp's own way when that tile is free.
     * Each press is verified ([FieldControl.awaitOutcome]): the warp taken, else at most [MAX_STEP_OFF_TRIES] tiles
     * tried, then a typed error.
     */
    private fun takeWarpHere(context: PlanContext, target: Target, trigger: WarpTrigger, field: FieldState, options: MoveOptions, mark: Int, notes: List<String>): Walk {
        val here = Node(field.x, field.y)
        when (trigger) {
            WarpTrigger.Never -> return Walk.Failed(neverTaken(target))
            is WarpTrigger.Press -> {
                val pushed = stepOnce(context, trigger.direction, Node(Int.MIN_VALUE, Int.MIN_VALUE), options)
                FieldControl.awaitOutcome(context, mark)?.let { return arrivedThrough(context, it, notes) }
                if (pushed is StepResult.Stopped) return Walk.Interrupted(pushed.state)
                if (pushed is StepResult.Failed) return Walk.Failed(pushed.error)
            }
            WarpTrigger.Enter -> {
                val area = context.game.world?.areaOf(field.mapId) ?: return Walk.NoRoute(RouteFailure.StartUnknown, noMapDetail(field))
                val pathfinder = Pathfinder(area, overlay(context, field, emptySet()))
                // Free tiles next to it, best first: the one behind the player's back (the step back then faces the way
                // the warp is usually entered).
                val sides = pathfinder.neighbours(here, routeOptions(field, options, emptySet(), StepWeights.NONE))
                    .filterIsInstance<Edge.Step>()
                    .sortedBy { if (field.facing != null && it.direction == field.facing.opposite) 0 else 1 }
                for (side in sides.take(MAX_STEP_OFF_TRIES)) {
                    val off = stepOnce(context, side.direction, side.to, options)
                    FieldControl.awaitOutcome(context, mark)?.let { return arrivedThrough(context, it, notes) }
                    when (off) {
                        is StepResult.Moved -> if (off.field.x != side.to.x || off.field.y != side.to.y) continue
                        is StepResult.Refused -> continue
                        is StepResult.Stopped -> return Walk.Interrupted(off.state)
                        is StepResult.Failed -> return Walk.Failed(off.error)
                    }
                    val back = stepOnce(context, side.direction.opposite, here, options, long = true)
                    FieldControl.awaitOutcome(context, mark)?.let { return arrivedThrough(context, it, notes) }
                    if (back is StepResult.Stopped) return Walk.Interrupted(back.state)
                    if (back is StepResult.Failed) return Walk.Failed(back.error)
                }
            }
        }
        val now = context.navigator.settle()
        if (now.screen !is Screen.Overworld || now.field == null) return Walk.Interrupted(now)
        return Walk.Stuck("${target.id} didn't take the player anywhere (still at ${now.field.x},${now.field.y}): " +
            when (trigger) {
                is WarpTrigger.Press -> "pressed ${trigger.direction.name.lowercase()} on it"
                else -> "stepped off it and back on"
            })
    }

    /** The error of a warp nothing takes ([WarpTrigger.Never]). */
    private fun neverTaken(target: Target) = ActionError.Unavailable(
        UnavailableReason.NO_PATH,
        "${target.id} at ${target.x},${target.y} can't be taken: its tile has no door, mat, stairs or entrance, nothing the game " +
            "warps the player from (it is only where the warp of the other side arrives)",
        "use another way out of this map",
    )

    /**
     * True when the walk reached the destination by stepping on it although it carried the player away: one of
     * [tiles] is a goal tile of [target] (a tile target, not one reached with A) that is a mechanism of [field]'s map
     * (a cart station, a platform trigger: [PuzzleSolving.isMechanism]). The walk is then done, wherever the ride ends.
     */
    private fun carriedFromGoal(field: FieldState, target: Target, goalTiles: Set<Pair<Int, Int>>, tiles: Iterable<Node>): Boolean =
        !target.adjacent && tiles.any { (it.x to it.y) in goalTiles && PuzzleSolving.isMechanism(field, it.x, it.y) }

    /**
     * Where a ride the walk ended on ([carriedFromGoal]) leaves the player: once they stand still with the control
     * again (a cart ride lasts several seconds), else [at] (the last place read) when it doesn't end in time.
     */
    private fun rideEnd(context: PlanContext, at: FieldState): FieldState =
        (FieldControl.awaitStill(context, maxFrames = TELEPORT_FRAMES) as? FieldControl.Still.Settled)?.state?.field ?: at

    /** How [walkTo] ends at a warp: where the player stands now, and which warp it was ([through]). */
    private fun arrivedThrough(context: PlanContext, warped: WarpWatch.Warped, notes: List<String>): Walk.Arrived =
        Walk.Arrived(warped.last.to, notes.toList(), through(context, warped))

    /**
     * The warp of [warped]: the warp or hole of the tile the player was last read on, else of the tile they were facing
     * (a door walked into), else of the tile behind them (side stairs: the climb's animation moves the player one tile
     * past the stairs before the fade), with that tile and its map.
     */
    internal fun through(context: PlanContext, warped: WarpWatch.Warped): Through {
        val from = warped.from
        val area = context.game.world?.areaOf(from.mapId)
        val tiles = listOf(from.x to from.y) + listOfNotNull(
            from.facing?.let { from.x + it.dx to from.y + it.dy },
            from.facing?.let { from.x - it.dx to from.y - it.dy },
        )
        val next = warped.next?.let { through(context, it) }
        for ((x, y) in tiles) {
            area?.warps?.firstOrNull { it.zone == from.mapId && it.x == x && it.y == y }?.let { return Through("warp:${it.id}", from, x, y, next) }
            area?.triggerWarps?.firstOrNull { it.zone == from.mapId && it.x == x && it.y == y }?.let { return Through("hole:${it.trigger}", from, x, y, next) }
        }
        return Through(null, from, from.x, from.y, next)
    }

    /**
     * A warp a walk went through: [id] (`warp:N`, `hole:N`; null when no warp of the map is known there), taken at
     * ([x], [y]) on the map of [from] (the last place read before it).
     */
    data class Through(val id: String?, val from: FieldState, val x: Int, val y: Int, val next: Through? = null) {
        /**
         * For the agent: "took warp:3 at 301,263 (Olivine City) → Lake of Rage (536,90)", [to] being where they stand;
         * each warp of a chain taken before the control came back ([next]): "took warp:3 … → Lake of Rage (536,90),
         * then took warp:1 … → …".
         */
        fun describe(to: FieldState): String {
            val arrived = next?.from ?: to
            return "took ${id ?: "a warp"} at $x,$y" + (if (arrived.mapId != from.mapId) " (${from.mapName})" else "") +
                " → ${arrived.mapName} (${arrived.x},${arrived.y})" + (next?.let { ", then " + it.describe(to) } ?: "")
        }
    }

    /**
     * For a target reached with A from next to it ([Target.adjacent], at a known tile): its tile and, when only its
     * height ([Target.talkHeight]) keeps them from being goals, the tiles next to it, for the diagnosis of a failed
     * route ([Pathfinder.diagnose]: the target never "stands in the way" of itself).
     */
    internal fun beside(area: Area, target: Target): Pathfinder.Beside? {
        if (!target.adjacent) return null
        val x = target.x ?: return null
        val y = target.y ?: return null
        return Pathfinder.Beside(x to y, if (target.talkHeight != null) goalTiles(area, target) else emptySet())
    }

    /** Tiles that end the walk: the target itself, or the tiles from which it can be reached with A. */
    internal fun goalTiles(area: Area, target: Target): Set<Pair<Int, Int>> {
        val x = target.x ?: return target.enter
        val y = target.y ?: return emptySet()
        if (!target.adjacent) return setOf(x to y)
        return Direction.entries.flatMap { d ->
            val next = x + d.dx to y + d.dy
            // Across a counter (Pokémon Center nurse, shop clerk), A reaches two tiles away.
            val across = area.tile(next.first, next.second)?.kind == TileKind.Counter
            if (across) listOf(x + 2 * d.dx to y + 2 * d.dy) else listOf(next)
        }.toSet()
    }

    /**
     * The live overlay of [field]'s map; unless [solve] (by default [ActionSettings.solvePuzzles]), one where routes
     * only walk ([PuzzleSolving.walkOnly]: no lift, no platform trigger, no ice block pushed).
     */
    internal fun overlay(context: PlanContext, field: FieldState, refused: Set<Pair<Node, Direction>>, solve: Boolean = context.settings.solvePuzzles): Overlay =
        overlay(context.game.world?.areaOf(field.mapId), field, refused, activeTriggers(context, field), solve, onThisMap = context.settings.hideDestinations, context.state().eventFlags)

    /**
     * The overlay of [field]'s map ([area]) with the live state: people and obstacles, the [refused] steps, the
     * active scene [triggers], closed shutters, teleports and moving floors. [onThisMap]: routes stay on the player's
     * map ([Overlay.zone]): while destinations are hidden (a way across the next map would reveal it), and for the
     * reachability of the agent's view (it only talks about this map). Unless [solve], routes only walk. [flags]: the
     * save's event flags, for the people of the other zones of the area ([neighbourObjects]).
     */
    internal fun overlay(
        area: Area?,
        field: FieldState,
        refused: Set<Pair<Node, Direction>>,
        triggers: Set<Pair<Int, Int>>,
        solve: Boolean,
        onThisMap: Boolean,
        flags: dev.kotlinds.pokemonclient.state.EventFlags?,
    ): Overlay {
        val live = liveOverlay(area, field, refused, triggers, flags).let { if (onThisMap) it.copy(zone = field.mapId) else it }
        return if (solve) live else PuzzleSolving.walkOnly(live, field)
    }

    private fun liveOverlay(area: Area?, field: FieldState, refused: Set<Pair<Node, Direction>>, triggers: Set<Pair<Int, Int>>, flags: dev.kotlinds.pokemonclient.state.EventFlags?): Overlay {
        val templates = area?.people.orEmpty().filter { it.zone == field.mapId }
        return Overlay(
            // An open door object has slid aside (into the wall): it stands in nobody's way.
            objects = neighbourObjects(area, field, flags) + field.objects.filter { it.open != true }.map { o ->
                LiveObject(
                    o.x, o.y, o.facing,
                    isFollower = o.kind == FieldObjectKind.FOLLOWER,
                    sightRange = sightRange(o, templates),
                    // A trainer turning on its own watches every way it turns to, not only where it faces now.
                    looks = templates.firstOrNull { "person:${it.id}" == o.id }?.looks.orEmpty(),
                    clearedBy = when (o.obstacle) {
                        ObstacleKind.CUT_TREE -> FieldMoveKind.CUT
                        ObstacleKind.SMASH_ROCK -> FieldMoveKind.ROCK_SMASH
                        ObstacleKind.BOULDER -> FieldMoveKind.STRENGTH
                        ObstacleKind.ICE_BLOCK, null -> null
                    },
                    // Whether an ice block still moves is the game's to tell (the map's puzzle, PuzzleState.iceBlocks).
                    iceBlock = o.obstacle == ObstacleKind.ICE_BLOCK && field.puzzle?.iceBlocks?.firstOrNull { it.block == o.id }?.movable == true,
                    fallsInto = field.puzzle?.boulderHoles?.firstOrNull { it.boulder == o.id && !it.fallen }?.let { it.hole.x to it.hole.y },
                )
            },
            refused = refused,
            // The puzzle's own triggers (cart stations, pads) are its teleports, not scenes: routes ride them.
            activeTriggers = triggers - puzzleTriggerTiles(field.puzzle),
            // Closed shutters, and the invisible objects that answer A (they stand on their tile like a wall).
            blockedTiles = field.puzzle?.barriers.orEmpty().filterNot { it.open }.flatMap { b -> b.tiles.map { it.x to it.y } }.toSet() +
                field.examinables.map { it.x to it.y },
            // Warp pads to this same map (the Saffron Gym's) move the player on the map like a puzzle's teleport.
            teleports = puzzleTeleports(field.puzzle) + area?.let { WorldLinks.sameZoneTeleports(it, field.mapId) }.orEmpty(),
            surfaces = puzzleSurfaces(field.puzzle),
        )
    }

    /**
     * The objects of the other zones of [field]'s area where the map's events place them: the game only loads the
     * objects of the player's zone, so what stands just past the border is unknown live until the player crosses it.
     * - the obstacles (Cut trees, Rock Smash rocks, Strength boulders, [dev.kotlinds.pokemonclient.world.WorldRouter.staticOverlay]):
     *   without them a route planned from the neighbour walks straight into the tree (NOTES: "auto-Cut bug across
     *   maps", Pewter → Viridian, Route 2's tree);
     * - the people there now by the save's event [flags] ([dev.kotlinds.pokemonclient.world.WorldRouter.knownPeople]),
     *   trainers with their sight: `avoid_trainers` keeps out of the sight of a trainer of the next map too (NOTES
     *   race: Sailor Harry, seen at once on crossing the border of a route planned to avoid trainers).
     * Zones whose objects are still loaded (the one just left: `person:N@zone`) are known live, so left out, like any
     * tile a live object already stands on.
     */
    internal fun neighbourObjects(area: Area?, field: FieldState, flags: dev.kotlinds.pokemonclient.state.EventFlags?): List<LiveObject> {
        if (area == null) return emptyList()
        val loaded = field.objects.mapNotNull { o -> o.id.substringAfter('@', "").toIntOrNull() }.toSet() + field.mapId
        val occupied = field.objects.map { it.x to it.y }.toSet()
        val obstacles = area.people
            .filter { it.obstacle != null && it.zone !in loaded }
            .map { LiveObject(it.x, it.y, it.facing, clearedBy = it.obstacle) }
        val people = dev.kotlinds.pokemonclient.world.WorldRouter.knownPeople(area, flags) { it !in loaded }
        return (obstacles + people).filter { (it.x to it.y) !in occupied }
    }

    /**
     * True when a live object (not the follower) stands on ([x], [y]) now: a step refused there was refused because of
     * it (an object of the zone just entered, loaded only now), not an invisible wall. Such a refusal isn't remembered
     * ([Overlay.refused]): the object itself is in the next plan, and remembering the move would also forbid the field
     * move that clears it from that tile (Cut facing the tree).
     */
    private fun objectAt(context: PlanContext, x: Int, y: Int): Boolean =
        context.navigator.settle().field?.objects.orEmpty().any { it.kind != FieldObjectKind.FOLLOWER && it.x == x && it.y == y }

    /**
     * The coordinate triggers [puzzle] models: the tiles of its teleports, and every tile its mechanism may ride from
     * ([dev.kotlinds.pokemonclient.world.PuzzleMechanics.triggerTiles]: a cart station without its cart does nothing).
     */
    internal fun puzzleTriggerTiles(puzzle: PuzzleState?): Set<Pair<Int, Int>> =
        puzzle?.teleports.orEmpty().flatMap { t -> t.from.map { it.x to it.y } }.toSet() + puzzle?.mechanics?.triggerTiles.orEmpty()

    /** The teleports of [puzzle] as route edges (heights converted to tile units). */
    internal fun puzzleTeleports(puzzle: PuzzleState?): List<TeleportLink> = puzzle?.teleports.orEmpty().flatMap { t ->
        t.from.map { TeleportLink(it.x, it.y, t.to.x, t.to.y, fromHeight = t.fromHeight?.times(FIELD_HEIGHT_UNITS), toHeight = t.toHeight?.times(FIELD_HEIGHT_UNITS)) }
    }

    /** The moving floors of [puzzle] (a lift platform) as live tile heights (tile units). */
    internal fun puzzleSurfaces(puzzle: PuzzleState?): Map<Pair<Int, Int>, List<Int>> =
        puzzle?.surfaces.orEmpty().flatMap { s -> s.tiles.map { (it.x to it.y) to s.heights.map { h -> h * FIELD_HEIGHT_UNITS } } }.toMap()

    /**
     * How far [o] watches for the player: 0 once beaten (a beaten trainer never stops the player again), else its
     * live reading ([dev.kotlinds.pokemonclient.state.FieldTrainer.sightRange]) or the map's template.
     */
    internal fun sightRange(o: dev.kotlinds.pokemonclient.state.FieldObject, templates: List<dev.kotlinds.pokemonclient.world.PersonTemplate>): Int {
        val trainer = o.trainer
        if (trainer?.defeated == true) return 0
        return trainer?.sightRange ?: templates.firstOrNull { "person:${it.id}" == o.id }?.sightRange ?: 0
    }

    /**
     * Tiles that run a script when stepped on right now: map triggers whose variable has the value they wait for
     * (the Ecruteak Gym pits while the puzzle is unsolved, story events...). Avoided unless targeted.
     */
    internal fun activeTriggers(context: PlanContext, field: FieldState): Set<Pair<Int, Int>> {
        val area = context.game.world?.areaOf(field.mapId) ?: return emptySet()
        val memory = context.scope.memory()
        // The one rule of the triggers ([Area.sceneTriggerTiles]), on the save read now: placeholders and armed triggers
        // whose script ends silently in the current story (the Viridian Gym's guide, see Trigger.quietWhen) are floor.
        return area.sceneTriggerTiles(field.mapId, { context.game.scriptVariable(memory, it) }, { context.game.scriptFlag(memory, it) })
    }

    /**
     * The route options of a walk: the movement, the field moves usable, the agent's [options], and always the
     * measured costs of turns ([RouteOptions.defaultTurnCost]) and the soft [weights] of what steps may start
     * ([stepWeights]): bans ([MoveOptions.avoidTallGrass], [MoveOptions.avoidTrainers]) stay the agent's choice.
     */
    internal fun routeOptions(field: FieldState, options: MoveOptions, fieldMoves: Set<FieldMoveKind>, weights: StepWeights) = RouteOptions(
        mode = field.movement,
        // Always the measured cost of a turn for this movement ([RouteOptions.defaultTurnCost]): straight lines win.
        turnCost = null,
        weights = weights,
        canSurf = FieldMoveKind.SURF in fieldMoves,
        avoidTallGrass = options.avoidTallGrass,
        avoidTrainers = options.avoidTrainers,
        acceptOneWay = options.acceptOneWay,
        fieldMoves = fieldMoves,
    )

    /**
     * The soft step weights of a walk from [state] ([StepWeights]): every zone's wild encounter chance as the game rolls
     * it now ([dev.kotlinds.pokemonclient.world.WorldSource.encounterChance]) and an unbeaten trainer's battle, in steps
     * of how the player will move on land ([FootPace]: the bike, running or walking; walking onto the tiles where wild
     * Pokémon appear unless [MoveOptions.runInEncounterAreas], which costs the time walking loses there);
     * - with a Repel at work ([FieldState.repelSteps]), the level of the first Pokémon able to fight: weaker wild
     *   Pokémon don't appear (a strong enough lead makes the grass free), for the steps it has left only: the route
     *   counts its steps and weighs the encounter tiles after them without it ([StepWeights.repel]), and the walker
     *   walks onto them from there ([runOnto]);
     * - the item the first Pokémon holds (a Cleanse Tag makes encounters rarer).
     */
    internal fun stepWeights(context: PlanContext, state: GameState, options: MoveOptions): StepWeights =
        StepWeights.of(context.game.world, encounterConditions(state, options))

    /** What the player brings to the wild encounter roll now ([EncounterConditions]): movement, Repel, the lead's item. */
    internal fun encounterConditions(state: GameState, options: MoveOptions): EncounterConditions {
        val field = state.field
        val pace = FootPace.of(field, options)
        val repelSteps = field?.repelSteps?.takeIf { it > 0 }
        val repelLevel = if (repelSteps != null) state.party.firstOrNull { !it.isEgg && it.hp > 0 }?.level else null
        val leadItem = state.party.firstOrNull()?.heldItem?.id?.value
        return EncounterConditions(pace.encounterTiles, repelLevel, leadItem, travelMovement = pace.land, repelSteps = repelSteps?.takeIf { repelLevel != null })
    }

    /**
     * Whether a walk on [area] with [options] holds B onto each tile: when it runs ([FootPace.land]), except onto the
     * tiles it walks to keep wild encounters rare ([StepWeights.walksOnto] of [weights] after the steps already taken,
     * [StepWeights.after]: a Repel wearing off on the way leaves the grass free, run through, for its steps left only).
     * The same weights and the same count of steps as the route was planned with ([Route.stepsBefore]): one source for
     * the plan and the walker.
     */
    internal fun runOnto(area: Area, field: FieldState, options: MoveOptions, weights: StepWeights): RunOnto {
        if (FootPace.of(field, options).land != MovementMode.RUN) return RunOnto { _, _ -> false }
        return RunOnto { node, taken -> area.tile(node.x, node.y)?.let { !weights.after(taken).walksOnto(it, area.zoneAt(node.x, node.y)) } ?: true }
    }

    /**
     * What a diagnosis tells of the Strength boulders a way seemed to cross ([boulderNotes] with their names on
     * [field]: "person:3 at 9,25").
     */
    internal fun boulderNotes(field: FieldState, blockers: dev.kotlinds.pokemonclient.world.Blockers): String {
        fun names(tiles: List<Pair<Int, Int>>) = tiles.map { (x, y) -> (field.objects.firstOrNull { it.x == x && it.y == y }?.let { "${it.id} " } ?: "") + "at $x,$y" }
        return boulderNotes(names(blockers.stuckBoulders), names(blockers.unprovenBoulders))
    }

    /**
     * The one wording of the Strength boulders of a diagnosis, on one map or across maps ([WorldTravel]): what the agent
     * should not try, the boulders whose pushes open nothing ([stuck], proven by the [PushPlanner]); and those kept as
     * the way although the proof gave up before deciding ([unproven]). "" when none.
     */
    internal fun boulderNotes(stuck: List<String>, unproven: List<String>): String =
        (if (stuck.isEmpty()) "" else "; pushing the boulder ${stuck.joinToString(", ")} opens no way there (a wall or a dead end behind it): it is not the way") +
            (if (unproven.isEmpty()) "" else "; whether pushing the boulder ${unproven.joinToString(", ")} opens the way couldn't be worked out " +
                "(too many boulder positions to try): it may be the way, not proven")

    /**
     * A route moving objects out of the way ([PushPlanner]: Strength boulders, ice blocks), when the overlay has
     * some the party can move; null otherwise or when the puzzle search finds none within its bound.
     */
    private fun pushRoute(area: Area, overlay: Overlay, start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, isGoal: (Node) -> Boolean): Route? {
        val planner = PushPlanner(area, overlay)
        if (!planner.hasMovables(options)) return null
        return planner.route(start, options, goalTiles, isGoal)
    }

    /**
     * The tiles of [field]'s active scene triggers ([Overlay.activeTriggers] of [overlay]) a walk stops on: not the
     * mechanisms that move the player on their own (teleport pads, lifts, platform triggers, holes, same-map warps:
     * handled as such), nor the [warpGoal] tiles (a hole or warp script targeted on purpose).
     */
    internal fun sceneTiles(area: Area, field: FieldState, overlay: Overlay, warpGoal: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        if (overlay.activeTriggers.isEmpty()) return emptySet()
        val moving = area.triggers.filter { t -> t.zone == field.mapId && (area.triggerWarps.any { it.zone == t.zone && it.trigger == t.id } || area.scriptWarps.any { it.zone == t.zone && it.trigger == t.id }) }
            .flatMapTo(mutableSetOf()) { it.tiles }
        val teleports = overlay.teleports.map { it.fromX to it.fromY }.toSet()
        return overlay.activeTriggers.filterTo(mutableSetOf()) { (x, y) ->
            (x to y) !in moving && (x to y) !in teleports && (x to y) !in warpGoal && !PuzzleSolving.isMechanism(field, x, y)
        }
    }

    /** After stepping on a scene trigger: lets the step end and the scene's script start, then waits for its first stop. */
    private fun sceneStarted(context: PlanContext): GameState {
        FieldControl.awaitStill(context)
        return context.navigator.settle()
    }

    /**
     * What to tell about [at], a tile where the walk stopped: the scene trigger there (`trigger:N`, its zone's
     * coordinate event) when one covers it, else null. It points to `blocked_by` only when the state lists that
     * trigger there ([dev.kotlinds.pokemonclient.state.StoryState.blockers], sent at every knowledge level): never to
     * a field the answer doesn't carry (a game without story blockers, a trigger they don't list).
     */
    internal fun sceneNote(context: PlanContext, mapId: Int?, at: Pair<Int, Int>?): String? {
        at ?: return null
        val area = mapId?.let { context.game.world?.areaOf(it) } ?: return null
        val trigger = sceneTriggerAt(area, mapId, at.first, at.second) ?: return null
        val id = "trigger:${trigger.id}"
        val listed = context.state().let { s -> s.field?.mapId == mapId && s.story?.blockers.orEmpty().any { it.target == id } }
        return "stepped on a scene trigger at ${at.first},${at.second} ($id), which started a scene" +
            if (listed) ": see blocked_by ($id) for what it does" else " (it may start again each time you step there until the story moves on)"
    }

    /** The coordinate trigger of zone [mapId] covering ([x], [y]) that runs a script (not a placeholder: [dev.kotlinds.pokemonclient.world.Trigger.inert]), or null. */
    internal fun sceneTriggerAt(area: Area, mapId: Int, x: Int, y: Int): dev.kotlinds.pokemonclient.world.Trigger? =
        area.triggers.firstOrNull { t -> t.zone == mapId && !t.inert && t.covers(x, y) }

    /** What the agent should know when the options asked to avoid something the only way crosses anyway. */
    private fun avoidanceNotes(route: Route, options: MoveOptions): List<String> = route.warnings.mapNotNull { w ->
        when {
            w is dev.kotlinds.pokemonclient.world.RouteWarning.CrossesTallGrass && options.avoidTallGrass ->
                "no way without tall grass here: the way crosses ${w.tiles} grass tile(s)"
            w is dev.kotlinds.pokemonclient.world.RouteWarning.PassesTrainerSight && options.avoidTrainers ->
                "no way around an unbeaten trainer's line of sight here"
            else -> null
        }
    }

    /** What one step did. */
    sealed interface StepResult {
        data class Moved(val field: FieldState) : StepResult
        data object Refused : StepResult
        data class Stopped(val state: GameState) : StepResult

        /**
         * A check before the press failed (a boulder's push: the player never turned to face it), nothing pressed
         * blindly: the typed [error] ([FieldMoveWalk.push]).
         */
        data class Failed(val error: ActionError) : StepResult
    }

    /**
     * Holds [direction] until the player starts moving, then lets go and waits for the move to finish (the player
     * still for a few frames). No movement within a short delay ([long] for jumps and doors) means the game refused
     * the step.
     *
     * For a forced move ([slide] > 0: the number of tiles of an [Edge.Slide] on ice or spinners), the press only
     * starts it: the wait lasts as long as the slide, and "still" means longer than the spin a spinner does on each
     * arrow, so the player is on the landing tile [to] when it returns (or elsewhere, and the caller re-plans).
     * B is held with it when [run] (a walk's [runOnto] for [to]).
     */
    fun stepOnce(context: PlanContext, direction: Direction, to: Node, options: MoveOptions, long: Boolean = false, slide: Int = 0, run: Boolean = options.run): StepResult {
        val buttons = buildSet {
            add(direction.button)
            if (run) add(Button.B)
        }
        val start = context.state().field ?: return StepResult.Stopped(context.state())
        val mark = FieldControl.warpMark(context)
        var frames = 0
        val limit = if (long) LONG_STEP_FRAMES else STEP_FRAMES
        while (frames < limit) {
            context.scope.step(1, InputFrame(buttons))
            frames++
            val state = context.state()
            val field = state.field
            // Not moving yet: any screen but the overworld (a fade, an animation) stops the hold (Motion.HOLD).
            if (field == null || FieldControl.takenOver(state, FieldControl.Motion.HOLD)) return StepResult.Stopped(state)
            val started = field.moving || field.x != start.x || field.y != start.y || field.mapId != start.mapId
            if (!started) continue
            // The move has started: let go at once (on a bike, holding one frame too long commits to the next
            // tile), then wait for it to end (the player still for a few frames) and read where the player really is.
            val still = FieldControl.awaitStill(
                context,
                maxFrames = FieldControl.SETTLE_FRAMES + slide * SLIDE_TILE_FRAMES,
                stillFrames = if (slide > 0) SLIDE_STILL_FRAMES else FieldControl.STILL_FRAMES,
            )
            val after = still.state
            val end = after.field
            if (end == null || FieldControl.takenOver(after, FieldControl.Motion.WALK)) return StepResult.Stopped(after)
            if (end.mapId != start.mapId || (end.x == to.x && end.y == to.y)) return StepResult.Moved(end)
            // Through a warp (even one back to this very tile): never press again, the caller sees where it led.
            if (context.navigator.warps.since(mark) != null) return StepResult.Moved(end)
            // Only turned to face the direction (no tile change): keep holding.
            if (end.x == start.x && end.y == start.y) continue
            return StepResult.Moved(end)
        }
        context.scope.step(1)
        return StepResult.Refused
    }

    /**
     * Steps onto the source tile of a teleport (warp pad, cart ride), then waits for its script to run and for the
     * player to stand still again, wherever that is. A battle or a message on the way stops the walk.
     */
    private fun teleport(context: PlanContext, edge: Edge.Teleport, options: MoveOptions): StepResult {
        val start = context.state().field ?: return StepResult.Stopped(context.state())
        val step = stepOnce(context, edge.direction, edge.via, options, long = true)
        if (step is StepResult.Refused || step is StepResult.Failed) return step
        // A lift keeps the player on its tile: its end is the height change.
        val viaHeight = (step as? StepResult.Moved)?.field?.height
        // The script starts at the end of the step: wait until it has moved the player (or for a while), then until
        // the player can walk again.
        var waited = 0
        var still = 0
        while (waited < TELEPORT_FRAMES) {
            context.scope.step(2)
            waited += 2
            val state = context.state()
            val field = state.field
            val awaiting = state.screen.awaiting
            if (FieldControl.takenOver(state, FieldControl.Motion.TRANSITION)) return StepResult.Stopped(state)
            val arrived = field != null && (field.mapId != start.mapId || field.x != edge.via.x || field.y != edge.via.y ||
                (edge.to.x == edge.via.x && edge.to.y == edge.via.y && viaHeight != null && field.height != viaHeight))
            val idle = field != null && state.screen is Screen.Overworld && awaiting == Awaiting.INPUT && !field.moving
            still = if (idle) still + 1 else 0
            // Ready once idle for a while after moving away, or idle long enough on the source tile (didn't fire).
            if (idle && ((arrived && still >= TELEPORT_STILL_POLLS) || still >= TELEPORT_IDLE_POLLS)) return StepResult.Moved(field!!)
        }
        val end = context.navigator.settle()
        return end.field?.let { StepResult.Moved(it) } ?: StepResult.Stopped(end)
    }

    // endregion

    // region Outcomes

    internal fun Walk.toOutcome(context: PlanContext, done: (FieldState) -> String): ActionOutcome = when (this) {
        // A walk ended by a warp on the way (not the destination: those are told by the trip, see WorldTravel).
        is Walk.Arrived -> ActionOutcome.Done(through?.let { stoppedAt(it, field) } ?: done(field))
        is Walk.NoRoute -> ActionOutcome.Failed(ActionError.Unavailable(if (failure is NeedsMechanism) UnavailableReason.PUZZLE_LEFT_TO_AGENT else UnavailableReason.NO_PATH, detail,
            (failure as? RouteFailure.NeedsFieldMove)?.let { FieldMoveWalk.hint(it, access[it.move], context.state().field) }
                ?: (if (context.settings.hideDestinations && failure == RouteFailure.Unreachable) UNREACHABLE_ON_THIS_MAP else null)
                ?: failure.hint(context.state().field)))
        is Walk.Stuck -> ActionOutcome.Failed(ActionError.Timeout(detail))
        is Walk.Failed -> ActionOutcome.Failed(error)
        is Walk.Interrupted -> {
            val by = cause ?: cause(context, state)
            ActionOutcome.Failed(ActionError.Interrupted(by, "$steps step(s)" + notes.joinToString("") { "; $it" } +
                (spotter(context, state)?.let { "; seen by $it" } ?: "") +
                (sceneNote(context, state.field?.mapId ?: context.state().field?.mapId, at)?.takeIf { by == InterruptionCause.SCRIPT }?.let { "; $it" } ?: "")))
        }
    }

    /**
     * The answer of a walk a warp ended before its destination: the walk stops at the first warp, the agent sees
     * where they are now before going on.
     */
    internal fun stoppedAt(through: Through, field: FieldState): String =
        "stopped on the way: ${through.describe(field)}, which wasn't the destination; look where you are, then go on from here"

    /** The map object of the trainer who saw the player in [state] (`person:N (label)`), when known. */
    private fun spotter(context: PlanContext, state: GameState): String? {
        val id = state.field?.engagedTrainerId ?: return null
        val objects = state.field.objects.ifEmpty { context.state().field?.objects.orEmpty() }
        return objects.firstOrNull { it.trainer?.trainerId == id }?.let { "${it.id} (${it.label})" }
    }

    /**
     * The hint of an unreachable target while destinations are hidden ([ActionSettings.hideDestinations]): the walks
     * never plan through other maps then, so the usual "nor through its warps" would be wrong.
     */
    private const val UNREACHABLE_ON_THIS_MAP = "not connected to where you stand by walking on this map (walls, heights); " +
        "destinations are hidden, so walks never go through other maps: take one of the exits and explore"

    /** Why there is no route, for the agent: what blocks and what to do about it. */
    internal fun RouteFailure.hint(field: FieldState?): String? {
        // A mechanism of the map the routes don't model may be what closes the way: say so rather than "walls".
        val unmodeled = field?.puzzle?.unmodeled
        if (unmodeled != null && (this == RouteFailure.Unreachable || this == RouteFailure.DifferentLevel)) {
            return "no route found, but this map has a mechanism the route planner doesn't model ($unmodeled): the way " +
                "probably goes through it. Work it out from field.puzzle and the map, and move step by step"
        }
        return plainHint(field)
    }

    private fun RouteFailure.plainHint(field: FieldState?): String? = when (this) {
        RouteFailure.OnlyOneWay -> "only by jumping down ledges (one way, no way back): retry with accept_one_way if that's fine"
        RouteFailure.DifferentLevel -> "it's on another height level (a walkway, a platform, a bridge above or below): " +
            "find the stairs, ladder or lift that leads there"
        RouteFailure.StartUnknown, RouteFailure.TargetUnknown -> null
        RouteFailure.Unreachable -> "not connected to where you stand by walking on this map (walls, heights), nor through its warps and holes"
        is RouteFailure.BlockedByPerson -> {
            val person = field?.objects?.firstOrNull { it.x == x && it.y == y }
            when {
                person == null -> "someone stands in the only way at $x,$y (on another floor or map): they may move, or step aside once spoken to"
                person.kind == FieldObjectKind.ITEM_BALL -> "an item ball lies in the only way at $x,$y: pick it up (interact ${objectTargetId(person)})"
                person.role == PersonRole.SHUTTER -> "a closed shutter (${person.id}) blocks the only way at $x,$y: flip the switches of this map's puzzle to open it"
                person.role == PersonRole.GATE -> "a closed door (${person.id}) blocks the only way at $x,$y: it opens after an event (a key, a battle...)"
                person.role == PersonRole.BLOCKER -> "${person.id} (${person.label}) blocks the only way at $x,$y until a story event moves them: " +
                    "follow the story (talk to them to learn what they wait for)"
                else -> "${person.id} (${person.label}) stands in the only way at $x,$y: talk to them (interact ${person.id}); " +
                    "some step aside once spoken to, others move by themselves; or leave the map and come back " +
                    "(people walking around go back to their place)"
            }
        }
        is RouteFailure.BlockedByBarrier -> {
            val barrier = field?.puzzle?.barriers?.firstOrNull { b -> b.tiles.any { it.x == x && it.y == y } }
            "a closed shutter${barrier?.let { " (${it.id})" } ?: ""} blocks the only way at $x,$y: flip the switches of this map's puzzle to open it"
        }
        is RouteFailure.NeedsFieldMove -> FieldMoveWalk.hint(this, null)
        is NeedsMechanism -> PuzzleSolving.hint(this, field)
        is RouteFailure.LongDetour -> shortWay?.let {
            "you decide: the way around (go_to a map of the way first, by its name, or fly closer), or the short way of ${it.links} warp(s) " +
                "through what you asked to avoid (go_to again with on_avoid_detour: short_way; each walk still avoids it where it can)"
        } ?: "the only way crosses $links warps and other maps: go_to a map of the way first (by its name), fly closer to it, or take " +
            "the whole loop (go_to again with on_local_detour: go)"
        is RouteFailure.ElevatorFloor -> operator.choice?.let { "$it, then take warp:$exitWarp" }
        is RouteFailure.Oscillation -> "the way found from here goes back through ${link.id}, already taken on this trip: something on the " +
            "way that the map data doesn't show closes it (a person, a door, an item ball); look at the map where you are and " +
            "go_to a place of this map first"
    }

    /**
     * Why the walk stopped in [state]. The holds let go at the first frame the game is busy, which may be a battle's
     * very first frames (the encounter's flash: no field, no battle read yet): what started is then read once the game
     * waits for input again.
     */
    private fun cause(context: PlanContext, state: GameState): InterruptionCause =
        cause(if (state.battle == null && state.field == null) context.navigator.settle() else state)

    private fun cause(state: GameState): InterruptionCause = when {
        state.battle != null -> if (state.battle.trainers.isEmpty()) InterruptionCause.WILD_BATTLE else InterruptionCause.TRAINER_SIGHT
        state.field?.trainerEncounter == true -> InterruptionCause.TRAINER_SIGHT
        (state.screen as? Screen.Dialogue)?.source == TextSource.PHONE -> InterruptionCause.PHONE_CALL
        state.screen is Screen.Unknown -> InterruptionCause.UNKNOWN_SCREEN
        else -> InterruptionCause.SCRIPT
    }

    private fun battleOrStop(context: PlanContext): ActionOutcome {
        val state = context.navigator.settle()
        return if (state.battle != null) ActionOutcome.Done("wild battle")
        else ActionOutcome.Failed(ActionError.Interrupted(cause(state), "walking in the grass"))
    }

    private fun notInField(context: PlanContext) =
        ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", context.state().screen))

    // endregion

    private fun Screen.Overworld.sameAsOverworld(other: Screen) = other is Screen.Overworld && other.banner == banner

    private const val STEP_FRAMES = 24
    /** Jumps and doors (the door opens first) take longer than a step. */
    private const val LONG_STEP_FRAMES = 64

    /** After a slide, still for longer than a spinner's spin on each arrow (7 frames, overlay 1 ov01_021F31CC). */
    private const val SLIDE_STILL_FRAMES = 16

    /** Extra wait per tile of a slide (a tile takes about 8 frames on ice, 8 plus the 7-frame spin on arrows). */
    private const val SLIDE_TILE_FRAMES = 24

    /** A silent switch's script (sound, shutters moving) runs a little while after A. */
    private const val SWITCH_FRAMES = 40
    private const val MAX_REPLANS = 6

    /** Longest wait for a teleport to end (a cart ride crosses the Azalea Gym in a few seconds). */
    private const val TELEPORT_FRAMES = 1800

    /** Polls (2 frames each) standing still after a teleport before walking on. */
    private const val TELEPORT_STILL_POLLS = 4

    /** Polls standing still on the source tile after which the teleport is considered not to have fired. */
    private const val TELEPORT_IDLE_POLLS = 45
    private const val MAX_PACING_STEPS = 200
    /** The target of [findEncounter]'s walk, as its messages name it. */
    private const val ENCOUNTER_GROUND = "a tile with wild Pokémon"

    /** Tiles next to a warp the player stands on tried to step off and back on it ([takeWarpHere]): the "3 tries" rule. */
    private const val MAX_STEP_OFF_TRIES = 3

    /** The target id of the nearest PC (`interact(pc)`). */
    const val PC = "pc"
    private const val INTERACT_TRIES = 3
    private const val PC_SEARCH = 24

    /** True when moving actions can start: walking (or surfing) freely, with the maps known. */
    fun canWalk(state: GameState, hasWorld: Boolean): Boolean =
        hasWorld && state.field != null && FieldControl.inControl(state)
}
