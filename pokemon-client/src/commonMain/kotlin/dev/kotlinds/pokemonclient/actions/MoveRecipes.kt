package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.MovePlans.AfterNotice
import dev.kotlinds.pokemonclient.actions.MovePlans.StepResult
import dev.kotlinds.pokemonclient.actions.MovePlans.Target
import dev.kotlinds.pokemonclient.actions.MovePlans.Walk
import dev.kotlinds.pokemonclient.actions.MovePlans.toOutcome
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WarpTrigger

/**
 * The recipes of the movement actions: `go_to`, `interact`, `step`, `find_encounter` and `push`. A family of the
 * chain of [RecipeBase], above [BagPartyRecipes]. Only the entry points live here: the walking engine they drive
 * (routes on the ROM's maps with the live people on top, walked one tile at a time and checked after each step:
 * [MovePlans], [WorldTravel], [FieldControl], [WalkSegments], [FieldMoveWalk], [PuzzleSolving]...) stays stateless
 * beside the chain, and carries out the actions it needs as steps (a Repel used again on the way, the bicycle...)
 * through the game's own recipes ([RecipeBase.perform]). Where each action can start from is the availability methods
 * below (the common rule [ActionConditions.canWalk]).
 */
abstract class MoveRecipes internal constructor() : BagPartyRecipes() {

    // region Availability: when each action of this family can run (read by the listing and the execution alike)

    /** `go_to`: walking freely ([ActionConditions.canWalk]), the map's people, items and signs as targets. */
    internal open fun goToAvailability(state: GameState): Availability =
        if (ActionConditions.canWalk(state, hasWorld = true)) Availability.Available(mapOf("target" to ActionConditions.targetChoices(state))) else Availability.Hidden

    /** `interact`: walking freely, the map's people, items and signs as targets. */
    internal open fun interactAvailability(state: GameState): Availability =
        if (ActionConditions.canWalk(state, hasWorld = true)) Availability.Available(mapOf("target" to ActionConditions.targetChoices(state))) else Availability.Hidden

    /** `step`: walking freely. */
    internal open fun stepAvailability(state: GameState): Availability =
        if (ActionConditions.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden

    /** `find_encounter`: walking freely. */
    internal open fun findEncounterAvailability(state: GameState): Availability =
        if (ActionConditions.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden

    /** `push`: walking freely on a map with Strength boulders (their holes said, Ice Path B1F). */
    internal open fun pushAvailability(state: GameState): Availability {
        if (!ActionConditions.canWalk(state, hasWorld = true)) return Availability.Hidden
        val field = state.field ?: return Availability.Hidden
        val holes = field.puzzle?.boulderHoles.orEmpty().filter { !it.fallen }.associateBy { it.boulder }
        val boulders = field.objects.filter { it.obstacle == ObstacleKind.BOULDER }
        if (boulders.isEmpty()) return Availability.Hidden
        return Availability.Available(mapOf("boulder" to boulders.map { b ->
            Choice(b.id, "boulder at ${b.x},${b.y}" + (holes[b.id]?.let { " (its hole: ${it.hole.x},${it.hole.y})" } ?: ""))
        }))
    }

    // endregion

    /** Walks to a tile or a target, on this map or another one ([WorldTravel]: through warps, holes and map edges). */
    override fun goTo(action: GameAction.GoTo, context: PlanContext): ActionOutcome = WorldTravel.goTo(action, context)

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
    override fun interact(action: GameAction.Interact, context: PlanContext): ActionOutcome {
        repeat(INTERACT_TRIES) {
            val target = MovePlans.resolve(context, action.target, null, null) ?: return MovePlans.unknownTarget(context, action.target)
            val talkedTo = context.state().field?.objects?.firstOrNull { MovePlans.objectTargetId(it) == action.target }
            val trainerId = talkedTo?.trainer?.trainerId
            // The tiles really moved (walk and turn), for an interruption after the walk arrived (see below).
            val counter = TileCounter()
            val walked = context.navigator.watching(counter::observe) { WorldTravel.walkNextTo(context, target.copy(adjacent = true)).walk }
            if (walked is Walk.Interrupted) engagedTarget(context, action.target, trainerId, walked.state, walked.steps)?.let { return it }
            if (walked is Walk.NoRoute && talkedTo?.height != null && (walked.failure == RouteFailure.Unreachable || walked.failure == RouteFailure.DifferentLevel)) {
                return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "no tile next to ${action.target} at its height (${talkedTo.height}) is reachable from here: ${walked.detail}",
                    "the game only answers A facing someone at your own height (from the water, a person on the shore above " +
                        "says nothing): find the way onto their level (a beach, stairs), then interact again"))
            }
            if (walked !is Walk.Arrived || walked.through != null) return walked.toOutcome(context) { "" }
            val field = walked.field
            val live = MovePlans.resolve(context, action.target, null, null) ?: target
            // Its height may only be known now (its map block loaded on the way): at another height than the player,
            // A would do nothing, walk again to a tile at its height (resolve now knows it) instead of pressing.
            val area = context.game.world?.areaOf(field.mapId)
            val height = area?.let { a -> field.objects.firstOrNull { MovePlans.objectTargetId(it) == action.target }?.let { MovePlans.knownHeight(a, it) } }
            if (height != null && height != field.height) return@repeat
            val facing = facingTowards(field, live) ?: return@repeat
            // Turned and checked before A (the verification rule): A facing elsewhere would talk to someone else.
            when (val faced = context.navigator.watching(counter::observe) { FieldControl.face(context, facing, "talk to ${action.target}") }) {
                is FieldControl.Facing.Faced -> Unit
                // Stopped while turning to it: the target (a trainer) seeing the player then is the battle asked for,
                // like on the way; anything else is an interruption after the tiles walked.
                is FieldControl.Facing.Stopped -> return engagedTarget(context, action.target, trainerId, faced.state, counter.tiles)
                    ?: Walk.Interrupted(faced.state, counter.tiles).toOutcome(context) { "" }
                is FieldControl.Facing.Failed -> return ActionOutcome.Failed(faced.error)
            }
            val beforeState = context.navigator.settle()
            val before = beforeState.screen
            val after = MovePlans.pressA(context, beforeState)
            val puzzleBefore = beforeState.field?.puzzle
            val puzzleAfter = after.field?.puzzle
            if (puzzleBefore != null && puzzleAfter != null && puzzleAfter != puzzleBefore) {
                return ActionOutcome.Done("the puzzle changed: ${puzzleChange(puzzleBefore, puzzleAfter)}")
            }
            if (!(after.screen is Screen.Overworld && after.screen.sameAsOverworld(before))) return ActionOutcome.Done("now: ${after.screen.kind}")
            // Nothing on screen: still facing it (it has nothing to say), or it walked away (try again).
            val now = after.field ?: return ActionOutcome.Done("now: ${after.screen.kind}")
            val stillThere = MovePlans.resolve(context, action.target, null, null)?.let { facingTowards(now, it) == now.facing } == true
            if (stillThere) return ActionOutcome.Done("faced ${action.target} and pressed A: no message (nothing to say, or nothing to do there)")
        }
        return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "Couldn't get right in front of ${action.target} (it keeps moving, or only a diagonal tile is reachable)"))
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
    override fun step(action: GameAction.Step, context: PlanContext): ActionOutcome {
        BikeRide.mount(context, action.options)
        val state = context.state()
        val start = state.field ?: return MovePlans.notInField(context)
        val mark = FieldControl.warpMark(context)
        val d = action.direction
        val tiles = (1..action.tiles).map { Node(start.x + d.dx * it, start.y + d.dy * it) }
        // Run, but walk onto the tiles where wild Pokémon appear (like go_to): the pace changes tile by tile, held on.
        val area = context.game.world?.areaOf(start.mapId)
        val runOnto = area?.let { MovePlans.runOnto(it, start, action.options, MovePlans.stepWeights(context, state, action.options)) } ?: RunOnto { _, _ -> action.options.run }
        var walked = WalkSegments.walk(context, WalkSegments.line(d, tiles, runOnto, taken = 0))
        // A message the game shows by itself on the way (a Repel wearing off): closed, then what the agent chose
        // ([MoveOptions.onRepelEnd]: stop there by default, or walk the rest of the line).
        val notes = mutableListOf<String>()
        var notices = 0
        while (notices++ < MovePlans.MAX_NOTICES) {
            val stopped = walked as? WalkSegments.Result.Stopped ?: break
            when (val notice = FieldControl.closeNotice(context, stopped.state)) {
                FieldControl.Notice.None -> break
                is FieldControl.Notice.Failed -> return ActionOutcome.Failed(notice.error)
                is FieldControl.Notice.Stopped -> {
                    notes += MovePlans.noticeNote(notice.notice, notice.state.field)
                    walked = WalkSegments.Result.Stopped(notice.state, stopped.walked)
                }
                is FieldControl.Notice.Closed -> {
                    val here = tiles.indexOfFirst { it.x == notice.field.x && it.y == notice.field.y }
                    val left = tiles.drop(here + 1)
                    val crosses = { area == null || MovePlans.crossesEncounters(area, MovePlans.stepWeights(context, context.state(), action.options), left) }
                    when (val next = MovePlans.afterNotice(context, notice, action.options, crosses)) {
                        is AfterNotice.Failed -> return ActionOutcome.Failed(next.error)
                        is AfterNotice.Stop -> return ActionOutcome.Failed(ActionError.Interrupted(next.cause,
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
        FieldControl.awaitOutcome(context, mark)?.takeUnless { PuzzleSolving.isRide(it) }?.let { return ActionOutcome.Done(MovePlans.through(context, it).describe(it.last.to) + told) }
        return when (val walked = walked) {
            is WalkSegments.Result.Reached -> ActionOutcome.Done("walked ${action.tiles} tile(s) ${d.name.lowercase()}, now at ${walked.field.x},${walked.field.y}$told")
            is WalkSegments.Result.Elsewhere -> ActionOutcome.Done("moved to ${walked.field.x},${walked.field.y} (not a straight walk: slid, pushed, or another map)")
            is WalkSegments.Result.Refused -> {
                val done = kotlin.math.abs(walked.from.x - start.x) + kotlin.math.abs(walked.from.y - start.y)
                // Walking into a boulder (Strength used) pushes it while the player stays: that's what the step did.
                pushedAhead(context, start, walked.from.x + d.dx, walked.from.y + d.dy)?.let { return ActionOutcome.Done(it) }
                // A wild battle or a scene starting as the player stopped looks like a refused step at first, and a
                // scene may take the control without leaving the field screen (Elm's aide walking up to the player in
                // his lab, NOTES: "blocked after 4 tile(s)" while she talked): blocked only when the player keeps the
                // control for a moment.
                FieldControl.takenAfterRefusal(context)?.let { taken ->
                    return ActionOutcome.Failed(ActionError.Interrupted(MovePlans.cause(context, taken), "$done tile(s)$told"))
                }
                // Standing on a warp taken by entering it (just arrived on it): the press did nothing, say how to take it.
                val underFeet = area?.warps
                    ?.firstOrNull { it.zone == start.mapId && it.x == walked.from.x && it.y == walked.from.y && it.trigger == WarpTrigger.Enter }
                ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "blocked after $done tile(s) at ${walked.from.x},${walked.from.y}: can't go ${d.name.lowercase()} from there (${MovePlans.refusalCause(context, walked.from, d)})",
                    underFeet?.let { "you stand on warp:${it.id}, taken by stepping onto it (pressing on it does nothing): go_to warp:${it.id} steps off and back on" }))
            }
            is WalkSegments.Result.Stopped -> ActionOutcome.Failed(ActionError.Interrupted(MovePlans.cause(context, walked.state), "${walked.walked} tile(s)$told"))
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
     * Walks to the nearest tile of this map where wild Pokémon appear ([encounterTile]: tall grass, a cave's floor, the
     * water when surfing), then back and forth there until one appears: every step onto such a tile is an encounter
     * check, so a single tile is paced from a neighbour.
     */
    override fun findEncounter(action: GameAction.FindEncounter, context: PlanContext): ActionOutcome {
        val state = context.state()
        val start = state.field ?: return MovePlans.notInField(context)
        val world = context.game.world ?: return noMap(start)
        val area = world.areaOf(start.mapId) ?: return noMap(start)
        val surfing = start.movement == MovementMode.SURF
        // Looking for wild Pokémon: running onto the encounter tiles doubles the chance of each step.
        val pacing = MoveOptions(runInEncounterAreas = true)
        val conditions = MovePlans.encounterConditions(state, pacing)
        val ground = if (surfing) "water" else "tall grass or cave floor"
        fun encounters(x: Int, y: Int, under: EncounterConditions = conditions) = MovePlans.encounterTile(world, area, start.mapId, x, y, surfing, under)
        val spot = Target(ENCOUNTER_GROUND, null, null, null, isGoal = { node -> encounters(node.x, node.y) })
        when (val walked = MovePlans.walkTo(context, spot, pacing)) {
            is Walk.Arrived -> if (walked.through != null) return walked.toOutcome(context) { "" }
            is Walk.Interrupted -> return if (walked.state.battle != null) ActionOutcome.Done("wild battle") else walked.toOutcome(context) { "" }
            is Walk.NoRoute -> {
                // A Repel keeping every wild Pokémon of the map away: the tiles are there, nothing can appear.
                val repelled = conditions.repelLevel != null && (area.zoneBounds[start.mapId] ?: return walked.toOutcome(context) { "" }).let { b ->
                    (b[1]..b[3]).any { y -> (b[0]..b[2]).any { x -> encounters(x, y, conditions.copy(repelLevel = null)) } }
                }
                val detail = if (repelled) "your Repel keeps the wild Pokémon of this map away (lead level ${conditions.repelLevel}): wait for it to wear off"
                else "no $ground with wild Pokémon reachable on this map from ${start.x},${start.y}"
                return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, detail))
            }
            else -> return walked.toOutcome(context) { "" }
        }
        // Pace: onto another encounter tile next to this one, or off this one and back (each entry is a check).
        repeat(MAX_PACING_STEPS) {
            val field = context.state().field ?: return battleOrStop(context)
            fun free(x: Int, y: Int) = area.tile(x, y)?.let { !it.blocked && it.kind != TileKind.Wall } == true &&
                field.objects.none { it.kind != FieldObjectKind.FOLLOWER && it.x == x && it.y == y } && (surfing || area.tile(x, y)?.kind !is TileKind.Water)
            val ways = Direction.entries.filter { d -> free(field.x + d.dx, field.y + d.dy) }
                .sortedByDescending { d -> encounters(field.x + d.dx, field.y + d.dy) }
            if (ways.isEmpty()) return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "no free tile next to ${field.x},${field.y} to pace to"))
            var moved = false
            for (d in ways) {
                when (val step = MovePlans.stepOnce(context, d, Node(field.x + d.dx, field.y + d.dy), pacing)) {
                    is StepResult.Moved -> moved = true
                    is StepResult.Stopped -> return battleOrStop(context)
                    // A battle starting as the step ends looks like a refused step at first: settle before trying another way.
                    is StepResult.Refused -> context.navigator.settle()
                    is StepResult.Failed -> return ActionOutcome.Failed(step.error)
                }
                if (context.state().battle != null || context.state().screen !is Screen.Overworld) return battleOrStop(context)
                if (moved) break
            }
            if (!moved) return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                "every step from ${field.x},${field.y} was refused (${MovePlans.refusalCause(context, Node(field.x, field.y), ways.first())})"))
        }
        return ActionOutcome.Failed(ActionError.Timeout("no wild Pokémon after $MAX_PACING_STEPS steps on the $ground"))
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

    private fun battleOrStop(context: PlanContext): ActionOutcome {
        val state = context.navigator.settle()
        return if (state.battle != null) ActionOutcome.Done("wild battle")
        else ActionOutcome.Failed(ActionError.Interrupted(MovePlans.cause(state), "walking in the grass"))
    }

    private fun Screen.Overworld.sameAsOverworld(other: Screen) = other is Screen.Overworld && other.banner == banner


    /**
     * `push`: plans with the [PushPlanner] ([PushPlanner.pushOnce] towards a direction, [PushPlanner.pushInto] into its
     * hole), then walks to each push and pushes, re-planning after each one. The agent's own act (it chose which
     * boulder and where it goes), so it runs whatever [ActionSettings.solvePuzzles] says.
     */
    override fun push(action: GameAction.Push, context: PlanContext): ActionOutcome {
        val done = mutableListOf<String>()
        var strengthUsed = false
        val direction = action.direction
        repeat(MAX_PUSHES) {
            val state = context.navigator.settle()
            val field = state.field
            if (field == null || state.screen !is Screen.Overworld) return interrupted(state, done)
            // Towards a direction: one push, then done (the answer says where it went).
            if (direction != null && done.isNotEmpty()) return ActionOutcome.Done(done.joinToString("; "))
            val hole = field.puzzle?.boulderHoles?.firstOrNull { it.boulder == action.boulder }
            if (direction == null) {
                if (hole == null) {
                    val boulders = field.objects.filter { it.obstacle == ObstacleKind.BOULDER }.map { it.id }
                    return ActionOutcome.Failed(if (action.boulder in boulders) ActionError.InvalidParameter("direction", "missing", Direction.entries.map { it.name.lowercase() })
                    else ActionError.InvalidParameter("boulder", action.boulder, boulders))
                }
                if (hole.fallen) {
                    return ActionOutcome.Done(
                        (if (done.isEmpty()) "${action.boulder} has already fallen through its hole" else "${action.boulder} fell through its hole at ${hole.hole.x},${hole.hole.y} to the floor below") +
                            done.joinToString("") { "; $it" },
                    )
                }
            }
            val boulder = field.objects.firstOrNull { it.id == action.boulder && it.obstacle == ObstacleKind.BOULDER }
                ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "${action.boulder} isn't a boulder on this floor"))
            val area = context.game.world?.areaOf(field.mapId)
                ?: return noMap(field)
            val access = FieldMoveWalk.access(context, state)
            strengthMissing(access[FieldMoveKind.STRENGTH])?.let { return ActionOutcome.Failed(it) }
            val options = MovePlans.routeOptions(field, MoveOptions(), FieldMoves.usable(access), MovePlans.stepWeights(context, state, MoveOptions()))
            // The puzzle's live state (shutters, people) as walks see it, every mechanism allowed: this is the agent's act.
            val overlay = MovePlans.overlay(context, field, emptySet(), solve = true)
            val start = Pathfinder(area, overlay).nodeOf(field)
            val planner = PushPlanner(area, overlay)
            val route = (if (direction != null) planner.pushOnce(start, options, boulder.x to boulder.y, direction) else planner.pushInto(start, options, boulder.x to boulder.y))
                ?: return ActionOutcome.Failed(if (direction != null) cantPush(action.boulder, boulder.x, boulder.y, direction, field.x, field.y)
                else ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "no way found to push ${action.boulder} (at ${boulder.x},${boulder.y}) into its hole at ${hole!!.hole.x},${hole.hole.y} from ${field.x},${field.y}" +
                        done.joinToString("") { "; $it" },
                    "something may block it (another boulder, a wall): leave the floor and come back to reset the boulders, or push it one tile at a time (direction)"))
            val index = route.edges.indexOfFirst { it is PushEdge }
            val edge = route.edges[index] as PushEdge
            val standAt = if (index == 0) start else route.edges[index - 1].to
            if (field.x != standAt.x || field.y != standAt.y) {
                // Walk only: no other boulder moved on the way (the plan keeps them where they are).
                val walker = context.with(context.settings.copy(solvePuzzles = false))
                val walked = MovePlans.walkTo(walker, MovePlans.Target("${standAt.x},${standAt.y}", standAt.x, standAt.y), MoveOptions())
                if (walked !is MovePlans.Walk.Arrived || walked.through != null) return with(MovePlans) { walked.toOutcome(context) { "" } }.withDone(done)
                if (walked.field.x != standAt.x || walked.field.y != standAt.y || walked.field.mapId != field.mapId) return@repeat
            }
            if (!strengthUsed) {
                when (val used = FieldMoveWalk.activateStrength(context, edge.direction)) {
                    is FieldMoveWalk.Use.Done -> strengthUsed = true
                    is FieldMoveWalk.Use.Stopped -> return interrupted(used.state, done)
                    is FieldMoveWalk.Use.Failed -> return ActionOutcome.Failed(used.error).withDone(done)
                }
            }
            val pushedTo = "pushed ${action.boulder} ${edge.direction.name.lowercase()} from ${edge.objectFrom.first},${edge.objectFrom.second} to ${edge.objectTo.first},${edge.objectTo.second}"
            when (val pushed = FieldMoveWalk.push(context, edge, MoveOptions())) {
                is MovePlans.StepResult.Moved -> done += pushedTo
                is MovePlans.StepResult.Stopped -> {
                    // The last push drops the boulder: the game says so ("The boulder fell down!"), read to its end.
                    val ownHole = hole?.takeIf { !it.fallen }?.hole
                    if (pushed.state.battle != null || ownHole == null || edge.objectTo.first != ownHole.x || edge.objectTo.second != ownHole.y) return interrupted(pushed.state, done)
                    done += "$pushedTo, where it fell through its hole to the floor below"
                    val read = context.navigator.advanceUntil(FALL_MESSAGE_PRESSES) { it.battle != null || it.screen is Screen.Overworld }
                    if (read !is Step.Done || read.value.screen !is Screen.Overworld) return interrupted(context.state(), done)
                }
                is MovePlans.StepResult.Failed -> return ActionOutcome.Failed(pushed.error).withDone(done)
                MovePlans.StepResult.Refused -> return ActionOutcome.Failed(ActionError.Timeout(
                    "the boulder at ${edge.objectFrom.first},${edge.objectFrom.second} didn't move when pushed ${edge.direction.name.lowercase()} from ${standAt.x},${standAt.y}",
                )).withDone(done)
            }
        }
        return ActionOutcome.Failed(ActionError.Timeout("${action.boulder} still hasn't fallen after $MAX_PUSHES pushes" + done.joinToString("") { "; $it" }))
    }

    /**
     * The refusal of a push towards [direction] the [PushPlanner] found no plan for: the tile behind the boulder refuses
     * it, or its other side can't be reached. Nothing moved.
     */
    private fun cantPush(id: String, x: Int, y: Int, direction: Direction, fromX: Int, fromY: Int): ActionError {
        val dir = direction.name.lowercase()
        val behind = "${x + direction.dx},${y + direction.dy}"
        val side = "${x - direction.dx},${y - direction.dy}"
        return ActionError.Unavailable(UnavailableReason.NO_PATH,
            "can't push $id (at $x,$y) $dir from $fromX,$fromY: nothing moved",
            "either $behind can't take it (a wall, water, a ledge, a warp, another object, a hole that isn't its own) or $side, " +
                "the tile to push it from, can't be reached without moving other boulders: try another direction or another boulder first")
    }

    /** The typed reason Strength can't be used, or null when it can. */
    private fun strengthMissing(access: FieldMoveAccess?): ActionError? = when (access) {
        is FieldMoveAccess.Usable -> null
        is FieldMoveAccess.NoBadge -> ActionError.Unavailable(UnavailableReason.NEEDS_BADGE, "Strength needs the ${access.badge} Badge")
        FieldMoveAccess.NoPokemon -> ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon of the party knows Strength")
        FieldMoveAccess.NotSupported -> ActionError.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "using Strength isn't supported in this game yet")
        FieldMoveAccess.Unknown, null -> ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "Strength can't be used in this game")
    }

    private fun interrupted(state: GameState, done: List<String>) = ActionOutcome.Failed(ActionError.Interrupted(
        if (state.battle != null) InterruptionCause.WILD_BATTLE else InterruptionCause.SCRIPT,
        (if (done.isEmpty()) "no push" else done.joinToString("; ")) + " (now: ${state.screen.kind})",
    ))

    private fun ActionOutcome.withDone(done: List<String>): ActionOutcome =
        if (done.isEmpty() || this !is ActionOutcome.Failed || error !is ActionError.Interrupted) this
        else ActionOutcome.Failed(error.copy(performed = error.performed + done.joinToString("") { "; $it" }))

    private companion object {
        const val MAX_PACING_STEPS = 200

        /** The target of [findEncounter]'s walk, as its messages name it. */
        const val ENCOUNTER_GROUND = "a tile with wild Pokémon"
        const val INTERACT_TRIES = 3

        /** Presses to read the message of the boulder's fall. */
        const val FALL_MESSAGE_PRESSES = 4

        /** Pushes at most per `push` (the Ice Path boulders need a handful). */
        const val MAX_PUSHES = 30
    }
}
