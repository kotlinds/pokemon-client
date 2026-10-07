package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.PokegearRadio
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveUse
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.TileKind

/**
 * The recipes of the field: what the start menu opens (`set_options`, `open_menu`, `save_game`), the field moves
 * used from the party (`fly`, `use_field_move`), fishing (`fish`) and the Pokégear's radio (`tune_radio`). A family of
 * the chain of [RecipeBase], above [ServiceRecipes]. The start menu, the party and the way back to the field are the
 * shared steps of [RecipeBase] ([openStartMenuEntry], [openParty], [closeToOverworld]), a key item is used through
 * [activateKeyItem]: a game whose menus differ overrides those steps, never these recipes.
 */
abstract class FieldRecipes internal constructor() : ServiceRecipes() {

    // region Availability: when each action of this family can run (read by the listing and the execution alike)

    /** `set_options`: in the field or on the OPTIONS screen, once the start menu has OPTIONS. */
    protected open fun setOptionsAvailability(state: GameState): Availability {
        ActionConditions.locked(state, StartMenuFeature.OPTIONS)?.let { return it }
        if (!ActionConditions.inField(state) && !ActionConditions.isOptionsScreen(state)) return Availability.Hidden
        val now = state.options
        return Availability.Available(now?.let {
            mapOf(
                "text_speed" to listOf(Choice(it.textSpeed.name.lowercase(), "now")),
                "battle_scene" to listOf(Choice(if (it.battleScene) "on" else "off", "now")),
                "battle_style" to listOf(Choice(it.battleStyle.name.lowercase(), "now")),
            )
        } ?: emptyMap())
    }

    /** `open_menu`: walking or on the start menu, once it opens; accepted, never offered. */
    protected open fun openMenuAvailability(state: GameState): Availability {
        val walking = FieldControl.inControl(state)
        val inMenu = (state.screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU
        return when {
            !walking && !inMenu -> Availability.Hidden
            state.startMenu?.contains(StartMenuFeature.BAG) == false ->
                Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The start menu doesn't open yet", "the story unlocks it (Mom gives it at the start)")
            else -> Availability.Available(listed = false)
        }
    }

    /** `save_game`: in the field, once the start menu has SAVE. */
    protected open fun saveGameAvailability(state: GameState): Availability =
        ActionConditions.locked(state, StartMenuFeature.SAVE) ?: if (ActionConditions.inField(state)) Availability.Available() else Availability.Hidden

    /** `fish`: walking freely, with a rod in the bag ([ActionConditions.RODS]). */
    protected open fun fishAvailability(state: GameState): Availability {
        if (!ActionConditions.canWalk(state, hasWorld = true)) return Availability.Hidden
        val rods = state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value in ActionConditions.RODS }
        return if (rods.isEmpty()) Availability.Hidden else Availability.Available(mapOf("rod" to rods.map { Choice("item:${it.item.id.value}", it.item.name) }))
    }

    /** `fly`: walking freely, by the game's Fly rule as the state read it ([GameState.fieldMoves]) and the map's flag. */
    protected open fun flyAvailability(state: GameState): Availability {
        // The game's Fly rule (the move, the badge by id: never its shown name, the game may be in French).
        // Null (a state not read by its game, tests) can't tell: hidden like a game without Fly.
        val fly = state.fieldMoves?.get(FieldMoveKind.FLY)
        return when {
            !ActionConditions.canWalk(state, hasWorld = true) || fly == null || fly == FieldMoveAccess.Unknown -> Availability.Hidden
            fly == FieldMoveAccess.NotSupported -> Availability.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "Fly isn't supported in this game yet (its party menu isn't decoded)")
            state.field?.flyAllowed == false -> Availability.Unavailable(UnavailableReason.NOT_FLYABLE_HERE, "Fly can't be used on this map (the map doesn't allow it)", "go to a map where Fly works")
            fly == FieldMoveAccess.NoPokemon -> Availability.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon in the party knows Fly")
            fly is FieldMoveAccess.NoBadge -> Availability.Unavailable(UnavailableReason.NEEDS_BADGE, "Fly needs the ${fly.badge} Badge")
            else -> Availability.Available()
        }
    }

    /** `use_field_move`: in the field, the moves of this action ([FieldMoveUse.ACTION]) the party can use now. */
    protected open fun useFieldMoveAvailability(state: GameState): Availability {
        if (!ActionConditions.inField(state)) return Availability.Hidden
        // The game's rules as the state read them (GameState.fieldMoves: the move known, the badge): the moves of
        // this action only (Fly and the moves walks use have their own ways).
        val access = state.fieldMoves.orEmpty().filterKeys { it.use == FieldMoveUse.ACTION }
        val usable = access.filterValues { it is FieldMoveAccess.Usable }
        return when {
            usable.isNotEmpty() -> Availability.Available(buildMap {
                put("move", usable.map { (kind, a) -> Choice(kind.wire, "${with(FieldMoveWalk) { kind.label() }} (${(a as FieldMoveAccess.Usable).monName})") })
                if (usable.keys.any { it.healsAnother }) put("target", ActionConditions.monChoices(state))
            })
            access.values.any { it is FieldMoveAccess.NoBadge } -> access.entries.first { it.value is FieldMoveAccess.NoBadge }.let { (kind, a) ->
                Availability.Unavailable(UnavailableReason.NEEDS_BADGE, "${with(FieldMoveWalk) { kind.label() }} needs the ${(a as FieldMoveAccess.NoBadge).badge} Badge")
            }
            access.values.any { it == FieldMoveAccess.NotSupported } ->
                Availability.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "field moves aren't supported in this game yet (its party menu isn't decoded)")
            else -> Availability.Hidden
        }
    }

    /**
     * `tune_radio`: walking, or on the Pokégear's radio or map (or its phone), in a game that has a Pokégear, once it
     * has the Radio Card. Offered on the radio itself; from the field it is accepted (it opens the Pokégear) without
     * being offered. A game without a Pokégear ([GameState.pokegear] false: Platinum, which has the Pokétch) doesn't
     * have the action at all: [Availability.NotInThisGame] on every screen, never listed.
     */
    protected open fun tuneRadioAvailability(state: GameState): Availability {
        if (state.pokegear == false) return Availability.NotInThisGame("This game has no Pokégear (so no radio)")
        val screen = state.screen
        val radio = (screen as? Screen.Viewer)?.radio
        val onGear = screen is Screen.Viewer && screen.app in POKEGEAR_VIEWERS ||
            (screen as? Screen.ListMenu)?.kind == MenuKind.PHONE_CONTACTS
        val walking = FieldControl.inControl(state)
        if (!onGear && !walking) return Availability.Hidden
        if (state.player?.pokegearCards?.contains(PokegearCard.RADIO) == false) {
            return Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The Pokégear has no Radio Card yet", "the Goldenrod Radio Tower's quiz gives it")
        }
        val stations = radio?.channels?.flatMap { it.stations } ?: RadioStation.entries.filter { it != RadioStation.COMMERCIALS }
        return Availability.Available(mapOf("station" to stations.map { Choice(it.wire, it.wire) }), listed = radio != null)
    }

    // endregion

    /**
     * The OPTIONS screen: start menu → OPTIONS, then for each setting asked for, the row (UP / DOWN) and the value
     * (RIGHT, wrapping), each press read back from the screen's entry ids (`setting:<row>:<value>`, never the shown
     * text); finally CONFIRM on the last row and A, which leaves saving them. The result is checked on the options read
     * from the save data.
     */
    override fun setOptions(action: GameAction.SetOptions, context: PlanContext): ActionOutcome {
        val wanted = buildList {
            action.textSpeed?.let { add("text_speed" to it.name.lowercase()) }
            action.battleScene?.let { add("battle_scene" to if (it) "on" else "off") }
            action.battleStyle?.let { add("battle_style" to it.name.lowercase()) }
        }
        if (wanted.isEmpty()) return ActionOutcome.Failed(ActionError.InvalidParameter("options", "none", listOf("text_speed", "battle_scene", "battle_style")))
        var step = openStartMenuEntry(context, "option:options").andThen {
            context.navigator.advanceUntil(OPTIONS_WAITS) { ActionConditions.isOptionsScreen(it) }
        }
        for ((row, value) in wanted + (EXIT_ROW to CONFIRM)) {
            step = step.andThen { setRow(context, row, value) }
        }
        step = step.andThen {
            context.navigator.confirm("CONFIRM", target = { it.id == "setting:$EXIT_ROW:$CONFIRM" })
        }.andThen {
            context.navigator.advanceUntil(OPTIONS_WAITS) { !ActionConditions.isOptionsScreen(it) && (it.screen is Screen.Overworld || it.screen is Screen.ListMenu) }
        }
        closeToOverworld(context)
        return step.then {
            val options = context.state().options
            val ok = options == null || (
                (action.textSpeed == null || options.textSpeed == action.textSpeed) &&
                    (action.battleScene == null || options.battleScene == action.battleScene) &&
                    (action.battleStyle == null || options.battleStyle == action.battleStyle)
                )
            if (!ok) ActionOutcome.Failed(ActionError.Timeout("the options read back are $options"))
            else ActionOutcome.Done(options?.let { "text speed ${it.textSpeed.name.lowercase()}, battle scene ${if (it.battleScene) "on" else "off"}, battle style ${it.battleStyle.name.lowercase()}" } ?: "options set")
        }
    }

    /** Brings the cursor on [row], then its value to [value] (RIGHT for the next value, LEFT for CONFIRM), re-reading after every press. */
    private fun setRow(context: PlanContext, row: String, value: String): Step<GameState> =
        context.navigator.select(Screen.ListMenu::class, row) { it.id.startsWith("setting:$row:") }.andThen {
            repeat(MAX_VALUE_PRESSES) {
                val state = context.navigator.settle()
                val menu = state.screen as? Screen.ListMenu ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the options", state.screen))
                val current = (menu.cursor as? Cursor.At)?.let { menu.entries.getOrNull(it.index) }?.id
                    ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the cursor on $row", menu))
                if (!current.startsWith("setting:$row:")) return@andThen Step.Failed(ActionError.VerificationFailed(row, row, current, 0))
                if (current == "setting:$row:$value") return@andThen Step.Done(state)
                // On the last row LEFT selects CONFIRM; elsewhere RIGHT cycles through the values.
                context.scope.tap(if (row == EXIT_ROW) Button.LEFT else Button.RIGHT)
                context.navigator.awaitChange(menu, maxFrames = VALUE_FRAMES)
            }
            Step.Failed(ActionError.Timeout("$row never became $value"))
        }

    /** Opens the start menu (X) and picks the entry; checks something else is on screen after. */
    override fun openMenu(action: GameAction.OpenMenu, context: PlanContext): ActionOutcome =
        openStartMenuEntry(context, action.entry).then { state ->
            val screen = state.screen
            if ((screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU) {
                ActionOutcome.Failed(ActionError.UnexpectedScreen("${action.entry} opened", screen))
            } else ActionOutcome.Done("now: ${screen.kind}")
        }


    /** Saves the game: start menu → SAVE → YES (→ YES again to overwrite another save), then waits for the end. */
    override fun saveGame(action: GameAction.SaveGame, context: PlanContext): ActionOutcome {
        return openStartMenuEntry(context, "option:save").andThen {
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
                    result = Step.Failed(ActionError.UnexpectedScreen("the end of the save", state.screen))
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
            closeToOverworld(context)
            ActionOutcome.Done("saved")
        }
    }

    /**
     * One cast of [GameAction.Fish.rod] (used from the bag) towards the water the player faces. A is pressed on the
     * very frame something bites, never before (too early reels the line in for nothing). Ends hooked (a wild
     * battle starts) or with nothing.
     */
    override fun fish(action: GameAction.Fish, context: PlanContext): ActionOutcome {
        val start = context.state()
        val field = start.field ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", start.screen))
        val facing = field.facing
        val ahead = facing?.let { context.game.world?.areaOf(field.mapId)?.tile(field.x + it.dx, field.y + it.dy)?.kind }
        if (ahead != null && !(ahead is TileKind.Water && ahead.fishable)) {
            return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NOT_FACING_WATER, "The player doesn't face water", "stand at the shore, facing the water"))
        }
        // Like any key item: with Y when the rod is registered there, else through the bag.
        val cast = activateKeyItem(context, action.rod)
        if (cast is Step.Failed) return ActionOutcome.Failed(cast.error)
        var bitten = false
        var frames = 0
        while (frames < FISH_FRAMES) {
            val state = context.state()
            if (state.battle != null) {
                // What bit: the wild Pokémon, read once the battle is ready (its data is filled as it starts).
                val ready = context.navigator.settle(maxFrames = BATTLE_START_FRAMES)
                val foe = ready.battle?.battlers?.firstOrNull { !it.ref.isPlayerSide }
                return ActionOutcome.Done("hooked a wild " + (foe?.let { "${it.species.name} Lv${it.level}" } ?: "Pokémon"))
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
                    return ActionOutcome.Done(if (bitten) "it got away" else "nothing bit")
                else -> context.scope.step(1)
            }
            frames++
        }
        return ActionOutcome.Failed(ActionError.Timeout("the cast didn't end"))
    }

    /**
     * Flies to a visited town: start menu → POKéMON → the Pokémon that knows Fly (read from the party in RAM, by move
     * id) → FLY → the town on the map (touched; when it's scrolled off screen, the D-pad moves the map one press at a
     * time until it shows) → YES. Checked on the map the player lands on.
     *
     * A visited town of the other region (HGSS: Fly only reaches the region the player is in) is reached in two
     * flights when the map has a region hub the player visited ([Screen.FlyMap.regionHub], Indigo Plateau): to the
     * hub, then from there to the town. Without it: OTHER_REGION ([FlyHints.regionError]).
     */
    override fun fly(action: GameAction.Fly, context: PlanContext): ActionOutcome {
        val startMap = context.state().field?.mapId
        val first = flyOnce(context, action.destination, startMap, hubAllowed = true)
        if (first is Step.Failed) {
            closeToOverworld(context)
            return ActionOutcome.Failed(first.error)
        }
        val (landed, viaHub) = (first as Step.Done).value
        if (viaHub == null) return ActionOutcome.Done(landedDetail(landed, startMap))
        // On the hub now: the second flight, from where every region can be chosen.
        return when (val second = flyOnce(context, action.destination, landed.field?.mapId, hubAllowed = false)) {
            is Step.Failed -> {
                closeToOverworld(context)
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
        var expected: Entry? = null
        return openFlyMap(context).andThen { state ->
            flyTarget(context, destination, state, startMap, hubAllowed)
        }.andThen { target ->
            hub = target.hub
            expected = target.entry
            context.scope.touch(target.touch)
            context.navigator.awaitChange(context.state().screen)
            context.navigator.advanceUntil(FLY_WAITS) { it.screen is Screen.YesNo }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (fly)") { it.id == "option:yes" }
        }.andThen { awaitLanding(context, startMap) }
            .andThen { landed -> checkLanding(context, landed, expected) }
            .andThen { landed -> Step.Done(landed to hub) }
    }

    /**
     * The landing checked against the fly point chosen ([entry], `fly:<map id>`: the map the game warps to, HGSS
     * `MapFlypointParam.mapIDforWarp`): a flight that lands elsewhere (a touch the map took for another town, a hub
     * flight going on to the wrong side) is a [ActionError.VerificationFailed], never told as arrived where asked.
     */
    private fun checkLanding(context: PlanContext, landed: GameState, entry: Entry?): Step<GameState> {
        val map = entry?.id?.removePrefix("fly:")?.toIntOrNull() ?: return Step.Done(landed)
        val field = landed.field ?: return Step.Done(landed)
        if (field.mapId == map) return Step.Done(landed)
        return Step.Failed(ActionError.VerificationFailed("fly", "landing in ${context.game.mapName(map)} (${entry.id})", "landed in ${field.mapName} at ${field.x},${field.y}", 1))
    }

    /** Start menu → POKéMON → a Pokémon that knows Fly → FLY ([openFieldMove]), up to the fly map. */
    private fun openFlyMap(context: PlanContext): Step<GameState> {
        // The game's Fly move (its rule), never a move name.
        val fly = context.game.fieldMoveRule(FieldMoveKind.FLY)?.move
        val flyers = fly?.let { FieldMoves.knowers(context.state(), it) }.orEmpty().sortedBy { it.fainted }
        return openFieldMove(context, FieldMoveKind.FLY, flyers).andThen { awaitFlyMap(context) }
    }

    /**
     * Start menu → POKéMON → one of [users] (Pokémon knowing [move], read from the party by move id, never a name, in
     * the order to try) → the move's entry ([FieldMoveKind.menuEntry]). Each Pokémon's menu is checked for the entry
     * before confirming; the next one is tried only if the game doesn't offer it there. The state once chosen.
     */
    private fun openFieldMove(context: PlanContext, move: FieldMoveKind, users: List<PartyMon>): Step<GameState> {
        val label = with(FieldMoveWalk) { move.label() }
        if (users.isEmpty()) return Step.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon in the party knows $label"))
        return openParty(context).andThen { state ->
            if (state.screen !is Screen.PartyGrid) return@andThen Step.Failed(ActionError.UnexpectedScreen("the party", state.screen))
            for (user in users) {
                val opened = context.navigator.choose(Screen.PartyGrid::class, user.displayName) { it.id == user.id.toString() }
                if (opened is Step.Failed) return@andThen opened
                val menu = context.navigator.settle().screen as? Screen.ContextMenu
                if (menu?.entries?.any { it.id == move.menuEntry } == true) {
                    return@andThen context.navigator.choose(Screen.ContextMenu::class, label.uppercase()) { it.id == move.menuEntry }
                }
                // Not offered: back to the party, then the next one.
                context.navigator.press(Button.B, menu ?: context.state().screen, maxFrames = 120)
            }
            Step.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "The game doesn't offer ${label.uppercase()} for ${users.joinToString { it.displayName }}", "heal them first"))
        }
    }

    /**
     * Uses a field move from the party menu (`use_field_move`): a Pokémon knowing it is picked from the party (by move
     * id: [FieldMoves.knowers]; for Milk Drink / Softboiled one with more than a fifth of its HP, the game's
     * condition, and never the [GameAction.UseFieldMove.target]), then [openFieldMove], then what the move does is
     * waited for and told: another map (Teleport, Dig), a wild battle (Sweet Scent, Headbutt), the target's HP (Milk
     * Drink, Softboiled), the game's messages. A message that leaves the party menu on screen is the game's refusal
     * (CANNOT_USE_HERE, with its text): where the move works is the game's own check, never assumed here (the map
     * randomizer allows Teleport in towns, for one).
     */
    override fun useFieldMove(action: GameAction.UseFieldMove, context: PlanContext): ActionOutcome {
        val move = action.move
        val label = with(FieldMoveWalk) { move.label() }
        val before = context.navigator.settle()
        val rule = context.game.fieldMoveRule(move) ?: return ActionOutcome.Failed(ActionError.Unsupported("use_field_move(${move.wire})"))
        when (val access = FieldMoves.of(before, context.game::fieldMoveRule)[move]) {
            is FieldMoveAccess.NoBadge -> return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NEEDS_BADGE, "$label needs the ${access.badge} Badge"))
            FieldMoveAccess.NoPokemon -> return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon in the party knows $label"))
            else -> Unit
        }
        val knowers = FieldMoves.knowers(before, rule.move)
        val target = action.target?.let { id -> before.party.firstOrNull { it.id == id } ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "$id isn't in the party")) }
        val users = if (move.healsAnother) {
            target ?: return ActionOutcome.Failed(ActionError.InvalidParameter("target", "missing", before.party.filter { !it.isEgg }.map { "${it.id} (${it.displayName})" }))
            knowers.filter { it.id != target.id && it.hp * HEAL_HP_SHARE > it.maxHp }.ifEmpty {
                return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE,
                    "No other Pokémon knowing $label has more than a fifth of its HP to give", "heal it first"))
            }
        } else knowers.sortedBy { it.fainted }
        val opened = openFieldMove(context, move, users)
        if (opened is Step.Failed) {
            closeToOverworld(context)
            return ActionOutcome.Failed(opened.error)
        }
        return if (target != null && move.healsAnother) healAnother(context, label, target) else fieldMoveResult(context, label, before)
    }

    /** The screens a field move plays through without input: its animations, the map changing, the party closing. */
    private fun fieldMovePlaying(screen: Screen) = screen is Screen.Animation || screen is Screen.Battle || screen is Screen.Unknown ||
        (screen is Screen.Overworld && screen.awaiting != Awaiting.INPUT)

    /**
     * After the move's entry was chosen: its messages read until the player walks again (what it did, from [before]),
     * a wild battle starts, or the party menu is back after a message (the game's refusal).
     */
    private fun fieldMoveResult(context: PlanContext, label: String, before: GameState): ActionOutcome {
        val said = mutableListOf<String>()
        // The party menu (or the Pokémon's menu) still drawn before anything was said: the move is starting, waited
        // through; back on the party after a message: the game refused it.
        val end = context.navigator.advanceUntil(
            FIELD_MOVE_WAITS,
            waitOn = { it -> fieldMovePlaying(it) || (said.isEmpty() && (it is Screen.PartyGrid || it is Screen.ContextMenu)) },
            onMessage = { s -> (s.screen as? Screen.Dialogue)?.text?.let(said::add) },
        ) { s ->
            s.battle != null || (s.screen is Screen.Overworld && s.screen.awaiting == Awaiting.INPUT) ||
                (s.screen is Screen.PartyGrid && s.screen.awaiting == Awaiting.INPUT && said.isNotEmpty())
        }
        if (end is Step.Failed) {
            closeToOverworld(context)
            return ActionOutcome.Failed(end.error)
        }
        val state = (end as Step.Done).value
        val text = said.joinToString(" ") { it.replace('\n', ' ') }.takeIf { it.isNotBlank() }
        if (state.screen is Screen.PartyGrid) {
            closeToOverworld(context)
            return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "The game refused $label here" + (text?.let { ": \"$it\"" } ?: "")))
        }
        if (state.battle != null) {
            val foe = context.navigator.settle(maxFrames = BATTLE_START_FRAMES).battle?.battlers?.firstOrNull { !it.ref.isPlayerSide }
            return ActionOutcome.Done("used $label: a wild " + (foe?.let { "${it.species.name} Lv${it.level}" } ?: "Pokémon") + " appeared")
        }
        val from = before.field
        val to = state.field
        val moved = from != null && to != null && (to.mapId != from.mapId || to.x != from.x || to.y != from.y)
        return ActionOutcome.Done("used $label" + (if (moved) ", now in ${to?.mapName} at ${to?.x},${to?.y}" else "") + (text?.let { ": \"$it\"" } ?: ""))
    }

    /**
     * Milk Drink / Softboiled, the user chosen: the party asks for the Pokémon to heal ([target], chosen by id), the
     * game says how much HP it got, then the menus are closed. Checked on [target]'s HP.
     *
     * The "on which Pokémon?" prompt is the party grid itself, waiting for a choice ([Screen.PartyGrid]: HGSS
     * `PARTY_MENU_STATE_SOFTBOILED`, src/party_menu.c, decoded as a grid whose purpose is OTHER, never a message), so it
     * ends the first wait; a message before it is the game's refusal (the user's HP too low: msg 127, then the party
     * menu again). A target the game refuses (the user itself, fainted or full: msg 120) prints its message and asks
     * again: its HP doesn't change, told with that message.
     */
    private fun healAnother(context: PlanContext, label: String, target: PartyMon): ActionOutcome {
        val said = mutableListOf<String>()
        val result = context.navigator.advanceUntil(FIELD_MOVE_WAITS, waitOn = ::fieldMovePlaying, onMessage = { s -> (s.screen as? Screen.Dialogue)?.text?.let(said::add) }) { s ->
            s.screen is Screen.PartyGrid && s.screen.awaiting == Awaiting.INPUT
        }.andThen {
            if (said.isNotEmpty()) return@andThen Step.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "The game refused $label: \"${said.joinToString(" ")}\""))
            context.navigator.choose(Screen.PartyGrid::class, target.displayName) { it.id == target.id.toString() }
        }.andThen {
            context.navigator.advanceUntil(FIELD_MOVE_WAITS, waitOn = ::fieldMovePlaying, onMessage = { s -> (s.screen as? Screen.Dialogue)?.text?.let(said::add) }) { s ->
                (s.screen is Screen.PartyGrid && s.screen.awaiting == Awaiting.INPUT) || s.screen is Screen.Overworld
            }
        }
        closeToOverworld(context)
        return result.then { _ ->
            val after = context.state().party.firstOrNull { it.id == target.id }
            val gained = (after?.hp ?: target.hp) - target.hp
            if (gained > 0) ActionOutcome.Done("used $label: ${target.displayName} got $gained HP (${after?.hp}/${after?.maxHp})")
            else ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "${target.displayName}'s HP didn't change" + (said.takeIf { it.isNotEmpty() }?.let { ": \"${it.joinToString(" ")}\"" } ?: "")))
        }
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
            // The map's header allowed it ([FieldState.flyAllowed]): the game said no for another reason (someone
            // travelling with the player, a disguise, the Safari Zone: src/field_move.c FieldMove_CheckFly).
            refused -> Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_FLYABLE_HERE, "The game refused Fly here (its message says why)", "try again after leaving this place"))
            else -> Step.Failed(ActionError.UnexpectedScreen("the fly map", context.state().screen))
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

    /**
     * Where to touch on the fly map, the hub's name when it is the region hub instead of the town asked, and the fly
     * point touched ([entry], to check the landing).
     */
    private data class FlyTarget(val touch: TouchPoint, val hub: String? = null, val entry: Entry? = null)

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
        val map = start.screen as? Screen.FlyMap ?: return Step.Failed(ActionError.UnexpectedScreen("the fly map", start.screen))
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
        return steerTo(context, entry).andThen { Step.Done(FlyTarget(it, viaHub, entry)) }
    }

    /**
     * The touch point of [entry] of the fly map: at once when it's on screen; else the cursor is steered towards the
     * town's cell, one press at a time (the map scrolls with it), until it can be touched. Each press is read back.
     */
    private fun steerTo(context: PlanContext, entry: Entry): Step<TouchPoint> {
        fun find(state: GameState) = (state.screen as? Screen.FlyMap)?.entries?.firstOrNull { it.id == entry.id }
        entry.touch?.let { return Step.Done(it) }
        repeat(MAP_STEER_PRESSES) {
            val map = context.navigator.settle().screen as? Screen.FlyMap ?: return Step.Failed(ActionError.UnexpectedScreen("the fly map", context.state().screen))
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

    private fun GameState.isSaveEnd() = screen is Screen.Overworld || (screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU


    /** `tune_radio`: opens the Pokégear's radio, tunes it, checks the station in RAM. */
    override fun tuneRadio(action: TuneRadio, context: PlanContext): ActionOutcome {
        val opened = openRadio(context)
        if (opened is Step.Failed) return ActionOutcome.Failed(opened.error)
        var radio = (opened as Step.Done).value
        val channel = radio.channels.firstOrNull { action.station in it.stations }
            ?: return ActionOutcome.Failed(ActionError.Unavailable(
                UnavailableReason.CANNOT_USE_HERE,
                "${action.station.wire} isn't on the dial here (band ${radio.band.name.lowercase()}: " +
                    radio.channels.flatMap { it.stations }.joinToString { it.wire }.ifEmpty { "no signal" } + ")",
                hintFor(action.station),
            ))
        var attempts = 0
        while (!(radio.tuned == channel.index && radio.clear)) {
            if (attempts > MAX_CORRECTIONS) {
                return ActionOutcome.Failed(ActionError.VerificationFailed(
                    "the radio's channel ${channel.index} (${action.station.wire})",
                    expected = "channel ${channel.index}, clear signal",
                    actual = radio.tuned?.let { "channel $it" + if (radio.clear) "" else " (static)" } ?: "between channels",
                    attempts = attempts,
                ))
            }
            // A preset first; a drag of the cursor when there is none or the preset didn't take.
            val preset = channel.preset
            if (preset != null && attempts == 0) context.scope.touch(preset) else dragDial(context, radio.cursor, channel.touch)
            attempts++
            radio = settledRadio(context) ?: return ActionOutcome.Failed(
                ActionError.UnexpectedScreen("the Pokégear radio", context.state().screen),
            )
        }
        // The programme starts a few frames after the channel is tuned.
        var waited = 0
        while (radio.station == null && waited < PROGRAMME_FRAMES) {
            context.scope.step(4)
            waited += 4
            radio = (context.state().screen as? Screen.Viewer)?.radio ?: break
        }
        val airing = radio.station
        val detail = buildString {
            append("tuned to channel ${channel.index}")
            airing?.let { append(": ${it.wire}") }
            radio.title?.let { append(" (\"$it\")") }
            if (airing != null && airing != action.station && airing != RadioStation.COMMERCIALS) {
                append("; this channel airs ${airing.wire} at this hour (${action.station.wire} at other hours)")
            }
        }
        if (!action.close) return ActionOutcome.Done(detail)
        closeToOverworld(context)
        val after = context.navigator.settle()
        if (after.screen !is Screen.Overworld) {
            return ActionOutcome.Failed(ActionError.UnexpectedScreen("the field after closing the Pokégear", after.screen))
        }
        val playing = after.field?.radioMusic
        return ActionOutcome.Done("$detail; Pokégear closed" + (playing?.let { ", ${it.wire} still playing" } ?: ""))
    }

    /** Opens the Pokégear's radio from the field, the phone or the map; returns its dial once it reads input. */
    private fun openRadio(context: PlanContext): Step<PokegearRadio> {
        repeat(MAX_OPEN_STEPS) {
            val state = context.navigator.settle()
            when (val screen = state.screen) {
                is Screen.Viewer -> when {
                    screen.app == ViewerApp.POKEGEAR_RADIO -> {
                        if (screen.awaiting == Awaiting.INPUT && screen.radio != null) return Step.Done(screen.radio)
                        context.scope.step(10)
                    }
                    screen.app == ViewerApp.POKEGEAR_MAP -> {
                        val button = screen.apps.firstOrNull { it.id == RADIO_APP }
                            ?: return Step.Failed(ActionError.NotOnScreen(RADIO_APP, screen.kind, screen.apps.map { it.label }))
                        if (!button.selectable) return Step.Failed(noRadioCard())
                        context.scope.touch(button.touch ?: return Step.Failed(ActionError.Unreachable("the radio", button.label)))
                        context.navigator.awaitChange(screen)
                    }
                    else -> return Step.Failed(ActionError.UnexpectedScreen("the Pokégear", screen))
                }
                is Screen.ListMenu -> {
                    if (screen.kind != MenuKind.PHONE_CONTACTS) return Step.Failed(ActionError.UnexpectedScreen("the Pokégear", (screen as Screen)))
                    if (screen.entries.firstOrNull { it.id == RADIO_APP }?.selectable == false) return Step.Failed(noRadioCard())
                    val touched = context.navigator.touchEntry(Screen.ListMenu::class, RADIO_APP, { it.id == RADIO_APP }, ActionError.Unreachable("the radio", RADIO_APP))
                    if (touched is Step.Failed) return touched
                }
                is Screen.Overworld -> {
                    if (state.battle != null) return Step.Failed(ActionError.Unavailable(UnavailableReason.IN_BATTLE, "Not during a battle"))
                    // Opening the Pokégear while the phone rings answers the call: the agent decides (advance_dialogue).
                    if (screen.incomingCall != null) return Step.Failed(ActionError.Interrupted(InterruptionCause.PHONE_CALL, "the phone rings (${screen.incomingCall.caller}): answer it first"))
                    val opened = openStartMenuEntry(context, GEAR_ENTRY)
                    if (opened is Step.Failed) return opened
                }
                is Screen.Animation -> context.scope.step(10)
                is Screen.Dialogue -> return Step.Failed(
                    if (screen.source == TextSource.PHONE) ActionError.Interrupted(InterruptionCause.PHONE_CALL, "a call started: advance_dialogue reads it")
                    else ActionError.UnexpectedScreen("the field or the Pokégear", screen),
                )
                else -> return Step.Failed(ActionError.UnexpectedScreen("the field or the Pokégear", screen))
            }
        }
        return Step.Failed(ActionError.Timeout("the Pokégear radio didn't open"))
    }

    /** The radio's dial once it reads input again, or null when the screen is no longer the radio. */
    private fun settledRadio(context: PlanContext): PokegearRadio? {
        val screen = context.navigator.settle(maxFrames = SETTLE_FRAMES).screen as? Screen.Viewer ?: return null
        return screen.radio.takeIf { screen.app == ViewerApp.POKEGEAR_RADIO }
    }

    /**
     * Drags the dial's cursor from [cursor] to [to]: the stylus goes down on the cursor (the radio only picks it up
     * within 8 pixels of it), moves a little every frame, rests on [to], then lifts.
     */
    private fun dragDial(context: PlanContext, cursor: TouchPoint, to: TouchPoint) {
        for (i in 0..DRAG_FRAMES) {
            val point = TouchPoint(cursor.x + (to.x - cursor.x) * i / DRAG_FRAMES, cursor.y + (to.y - cursor.y) * i / DRAG_FRAMES)
            context.scope.step(1, InputFrame(touch = point))
        }
        context.scope.step(DRAG_HOLD_FRAMES, InputFrame(touch = to))
        context.scope.step(DRAG_RELEASE_FRAMES)
    }

    private fun noRadioCard() = ActionError.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The Pokégear has no Radio Card yet", "the Goldenrod Radio Tower's quiz gives it")

    /** Where a station that isn't on this dial can be heard. */
    private fun hintFor(station: RadioStation): String = when (station) {
        RadioStation.POKE_FLUTE -> "the Poké Flute airs in Kanto once the Pokégear has the Expansion Card (Lavender Radio Tower's director, after the Power Plant is restored)"
        RadioStation.UNOWN -> "only in the Ruins of Alph"
        RadioStation.TEAM_ROCKET -> "only during Team Rocket's Radio Tower takeover"
        RadioStation.MAHOGANY_SIGNAL -> "only around Mahogany Town until the Rocket hideout is cleared"
        else -> "outside caves, in Johto or in Kanto once its Power Plant is restored"
    }

    private companion object {
        const val EXIT_ROW = "exit"
        const val CONFIRM = "confirm"
        const val OPTIONS_WAITS = 40
        const val MAX_VALUE_PRESSES = 6
        const val VALUE_FRAMES = 20

        const val FISH_FRAMES = 60 * 30

        /** The battle's intro, up to the command menu. */
        const val BATTLE_START_FRAMES = 900

        /** The cast animation: before it ends, the overworld still shows. */
        const val CAST_FRAMES = 90

        /** A Pokémon gives HP at most when it has more than 1 / [HEAL_HP_SHARE] of its HP (Milk Drink, Softboiled). */
        const val HEAL_HP_SHARE = 5

        /** How long the messages and animations of a field move may last (presses of [Navigator.advanceUntil]). */
        const val FIELD_MOVE_WAITS = 40
        const val FLY_WAITS = 40
        const val FLY_LANDING_FRAMES = 60 * 20
        const val LANDING_POLL = 4
        const val MAP_STEER_PRESSES = 80
        const val SAVE_ROUNDS = 2
        const val SAVE_WAITS = 120

        const val RADIO_APP = "app:radio"
        const val GEAR_ENTRY = "option:pokegear"

        /** The Pokégear apps `tune_radio` starts from besides the field (the phone is a list menu). */
        val POKEGEAR_VIEWERS = setOf(ViewerApp.POKEGEAR_RADIO, ViewerApp.POKEGEAR_MAP)
        const val MAX_OPEN_STEPS = 8
        const val MAX_CORRECTIONS = 3
        const val SETTLE_FRAMES = 120
        const val PROGRAMME_FRAMES = 60
        const val DRAG_FRAMES = 20
        const val DRAG_HOLD_FRAMES = 6
        const val DRAG_RELEASE_FRAMES = 4
    }
}
