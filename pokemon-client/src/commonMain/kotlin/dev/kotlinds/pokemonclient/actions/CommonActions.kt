package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import kotlinx.serialization.json.booleanOrNull
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleStyle
import dev.kotlinds.pokemonclient.state.TextSpeed
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveUse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The actions shared by every game: their specs (the contract; how each is carried out is the game's [Recipes]). */
object CommonActions {

    private val both = setOf(ActionMode.PURE, ActionMode.ASSISTED)
    private val assisted = setOf(ActionMode.ASSISTED)

    val press = ActionDefinition(GameAction.Press::class, spec(
        name = "press",
        description = "Press one button once, like a human (A confirms / talks / advances text, B cancels, X opens the menu, D-pad moves). " +
            "In the field, a D-pad press while facing another direction only turns the player (no step): to walk, use step.",
        parameters = listOf(Parameter("button", ParameterType.STRING, "The button.", values = Button.entries.map { it.name.lowercase() })),
        modes = both,
        availability = { Availability.Available() },
        parse = { GameAction.Press(button(it)) },
        enumerate = { Button.entries.map(GameAction::Press) },
    ))

    val touch = ActionDefinition(GameAction.Touch::class, spec(
        name = "touch",
        description = "Touch the bottom (touch) screen at x (0-255), y (0-191) pixels of the bottom screen. On a screenshot of both " +
            "screens (256x384, the top screen first), the bottom screen's y is the screenshot's y minus 192.",
        parameters = listOf(
            Parameter("x", ParameterType.INTEGER, "Horizontal position, 0 (left) to 255."),
            Parameter("y", ParameterType.INTEGER, "Vertical position, 0 (top) to 191."),
        ),
        modes = both,
        availability = { Availability.Available() },
        parse = { GameAction.Touch(TouchPoint(int(it, "x", 0..255), int(it, "y", 0..191))) },
    ))

    val wait = ActionDefinition(GameAction.Wait::class, spec(
        name = "wait",
        description = "Let the game run: a number of frames (60 per second), or until it waits for you again when omitted; " +
            "with until=change, until something changes on screen (new text, screen, cursor), at most `frames` (30 s by default).",
        parameters = listOf(
            Parameter("frames", ParameterType.INTEGER, "Frames to wait (1-1800).", required = false),
            Parameter("until", ParameterType.STRING, "input (default): until the game waits for you; change: until the screen changes.", required = false, values = listOf("input", "change")),
        ),
        modes = both,
        availability = { Availability.Available() },
        parse = { json ->
            val until = json["until"]?.jsonPrimitive?.contentOrNull?.lowercase()
            if (until != null && until != "input" && until != "change") throw ActionException(ActionError.InvalidParameter("until", until, listOf("input", "change")))
            GameAction.Wait(json["frames"]?.jsonPrimitive?.intOrNull?.also { check(it, "frames", 1..1800) }, untilChange = until == "change")
        },
        enumerate = { listOf(GameAction.Wait()) },
    ))

    val advanceDialogue = ActionDefinition(GameAction.AdvanceDialogue::class, spec(
        name = "advance_dialogue",
        description = "Read the messages through to the end (every page is returned), stopping at the first choice (already on a choice: does nothing). When the phone rings (incoming_call), answers it first.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            when (val screen = state.screen) {
                is Screen.Dialogue, is Screen.PressToContinue -> Availability.Available()
                is Screen.Overworld -> if (screen.incomingCall?.answer != null) Availability.Available() else Availability.Hidden
                // Nothing to read: accepted as a no-op, so a chain like `press a` → `advance_dialogue` doesn't fail.
                is Screen.Selectable -> Availability.Available(listed = false)
                else -> Availability.Hidden
            }
        },
        parse = { GameAction.AdvanceDialogue },
        enumerate = { listOf(GameAction.AdvanceDialogue) },
    ))

    val choose = ActionDefinition(GameAction.Choose::class, spec(
        name = "choose",
        description = "Choose an entry of the menu on screen by its id (the cursor is moved and checked for you).",
        parameters = listOf(Parameter("entry", ParameterType.STRING, "Id of the entry, from the menu's entries.")),
        modes = assisted,
        availability = { state ->
            val menu = state.screen as? Screen.Selectable ?: return@spec Availability.Hidden
            Availability.Available(mapOf("entry" to menu.entries.filter { it.selectable }.map { Choice(it.id, it.label) }))
        },
        parse = { GameAction.Choose(string(it, "entry")) },
        enumerate = { state -> (state.screen as? Screen.Selectable)?.entries?.filter { it.selectable }?.map { GameAction.Choose(it.id) }.orEmpty() },
    ))

    val attack = ActionDefinition(GameAction.Attack::class, spec(
        name = "attack",
        description = "Use a move of the active Pokémon this turn (in double battles, also give the target).",
        parameters = listOf(
            Parameter("move", ParameterType.STRING, "The move: its id (move:33) or its name."),
            Parameter("target", ParameterType.STRING, "Double battles: the target.", required = false, values = BattlerRef.entries.map { it.wire }),
        ),
        modes = assisted,
        availability = { state ->
            val battle = state.battle ?: return@spec Availability.Hidden
            // From the command menu, or from the battle's move list already open (a previous attempt left it there).
            if (state.screen !is Screen.BattleCommand && (state.screen as? Screen.MoveSelect)?.context != MoveContext.BATTLE) return@spec Availability.Hidden
            val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: BattlerRef.PLAYER_LEFT) } ?: return@spec Availability.Hidden
            Availability.Available(mapOf("move" to BattleMoveChoice.choices(actor).map { (id, label) -> Choice(id, label) }))
        },
        parse = { json -> GameAction.Attack(MoveRef(string(json, "move")), json["target"]?.jsonPrimitive?.contentOrNull?.let(::battler)) },
        enumerate = { state ->
            val battle = state.battle ?: return@spec emptyList()
            val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: BattlerRef.PLAYER_LEFT) } ?: return@spec emptyList()
            val foes = if (battle.isDouble) battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }.map { it.ref } else listOf(null)
            BattleMoveChoice.choices(actor).flatMap { (id, _) -> foes.map { GameAction.Attack(MoveRef(id), it) } }
        },
    ))

    val run = ActionDefinition(GameAction.Run::class, spec(
        name = "run",
        description = "Flee the wild battle.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            val battle = state.battle
            when {
                battle == null || state.screen !is Screen.BattleCommand -> Availability.Hidden
                battle.kind != BattleKind.WILD -> Availability.Unavailable(UnavailableReason.TRAINER_BATTLE, "There's no running from a trainer battle")
                else -> Availability.Available()
            }
        },
        parse = { GameAction.Run },
        enumerate = { listOf(GameAction.Run) },
    ))

    val keepBattling = ActionDefinition(GameAction.KeepBattling::class, spec(
        name = "keep_battling",
        description = "Keep your Pokémon in when the opponent is about to send in a new one (waits for the question if the messages before it still scroll).",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            val screen = state.screen
            when {
                (screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP -> Availability.Available()
                // The foe's Pokémon fainted: EXP / level-up messages come before the question.
                state.battle?.kind == BattleKind.TRAINER && (screen is Screen.Dialogue || screen is Screen.PressToContinue || screen is Screen.Battle) ->
                    Availability.Available(listed = false)
                else -> Availability.Hidden
            }
        },
        parse = { GameAction.KeepBattling },
        enumerate = { listOf(GameAction.KeepBattling) },
    ))

    val reorderParty = ActionDefinition(GameAction.ReorderParty::class, spec(
        name = "reorder_party",
        description = "Move a Pokémon of your party to a position (1 = the lead, sent out first in battles): it swaps places with the Pokémon there. " +
            "Or give `order`, the whole new order at once.",
        parameters = listOf(
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).", required = false),
            Parameter("position", ParameterType.INTEGER, "New position, 1 to 6.", required = false),
            Parameter(
                "order", ParameterType.ARRAY,
                "Instead of pokemon / position: the new order, [\"mon:…\", \"mon:…\", …] (the first one leads; Pokémon left out keep the remaining places).",
                required = false,
            ),
        ),
        modes = assisted,
        availability = { state -> if (PartyBagPlans.inField(state) && state.party.size > 1) Availability.Available(mapOf("pokemon" to monChoices(state))) else Availability.Hidden },
        parse = { json -> parseReorderParty(json) },
    ))

    val takeItem = ActionDefinition(GameAction.TakeItem::class, spec(
        name = "take_item",
        description = "Take back the item held by a Pokémon.",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state ->
            val holders = state.party.filter { it.heldItem != null }
            when {
                !PartyBagPlans.inField(state) -> Availability.Hidden
                holders.isEmpty() -> Availability.Unavailable(UnavailableReason.NO_STOCK, "No Pokémon holds an item")
                else -> Availability.Available(mapOf("pokemon" to holders.map { Choice(it.id.toString(), "${it.displayName} (${it.heldItem?.name})") }))
            }
        },
        parse = { json -> GameAction.TakeItem(mon(json, "pokemon")) },
    ))

    val giveItem = ActionDefinition(GameAction.GiveItem::class, spec(
        name = "give_item",
        description = "Give an item from the bag to a Pokémon to hold.",
        parameters = listOf(
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…)."),
            Parameter("item", ParameterType.STRING, "The item: its id (item:17) or its name."),
        ),
        modes = assisted,
        availability = { state -> if (PartyBagPlans.inField(state)) Availability.Available(mapOf("pokemon" to monChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.GiveItem(mon(json, "pokemon"), ItemRef(string(json, "item"))) },
    ))

    val useItem = ActionDefinition(GameAction.UseItem::class, spec(
        name = "use_item",
        description = "Use an item from the bag, in the field or in battle (on a Pokémon when the item needs one: Potion, Revive, Rare Candy...; " +
            "give `move` for an Ether-like item). The battle pocket is found for you. In the field, `items` uses several items in one go.",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The item: its id (item:17) or its name."),
            Parameter("target", ParameterType.STRING, "The Pokémon's id (mon:…), for items used on a Pokémon.", required = false),
            Parameter("move", ParameterType.STRING, "For PP restoring items (Ether, Max Ether, PP Up): the move (move:221 or its name).", required = false),
            Parameter(
                "items", ParameterType.ARRAY,
                "Field only, instead of item / target / move: a list of uses [{\"item\": …, \"target\": …, \"move\": …}, …] done one after the other in the same bag session.",
                required = false,
                fields = listOf(
                    Parameter("item", ParameterType.STRING, "The item: its id (item:17) or its name."),
                    Parameter("target", ParameterType.STRING, "The Pokémon's id (mon:…), for items used on a Pokémon.", required = false),
                    Parameter("move", ParameterType.STRING, "For PP restoring items: the move (move:221 or its name).", required = false),
                ),
            ),
        ),
        modes = assisted,
        availability = { state ->
            when {
                state.battle != null -> if (ActionConditions.canUseItemInBattle(state)) Availability.Available(itemChoices(state, inBattle = true)) else Availability.Hidden
                PartyBagPlans.inField(state) -> Availability.Available(itemChoices(state, inBattle = false))
                else -> Availability.Hidden
            }
        },
        parse = { json -> parseUseItem(json) },
    ))

    /** Items worth offering to `use_item` (medicine, berries, battle items; key items have their own action) and the targets. */
    private fun itemChoices(state: GameState, inBattle: Boolean): Map<String, List<Choice>> {
        val pockets = if (inBattle) setOf("medicine", "berries", "battle_items") else setOf("medicine", "berries", "items", "battle_items")
        val items = state.bag.orEmpty().filter { it.name in pockets }.flatMap { it.items }.filter { it.quantity > 0 }
            .map { Choice("item:${it.item.id.value}", "${it.item.name} x${it.quantity}") }
        return mapOf("item" to items, "target" to monChoices(state))
    }

    /** `use_item` from JSON: one use (item / target / move) or a list (`items`, as a JSON array or a JSON string of one). */
    private fun parseUseItem(json: JsonObject): GameAction.UseItem {
        val list = when (val raw = json["items"]) {
            null, is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonArray -> raw
            is kotlinx.serialization.json.JsonPrimitive -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw.content) as kotlinx.serialization.json.JsonArray }
                .getOrElse { throw ActionException(ActionError.InvalidParameter("items", raw.content, listOf("[{\"item\": \"Potion\", \"target\": \"mon:…\"}, …]"))) }
            else -> throw ActionException(ActionError.InvalidParameter("items", raw.toString()))
        }
        val uses = list?.map { element ->
            val use = element as? JsonObject ?: throw ActionException(ActionError.InvalidParameter("items", element.toString()))
            itemUse(use)
        } ?: listOf(itemUse(json))
        if (uses.isEmpty()) throw ActionException(ActionError.InvalidParameter("items", "[]"))
        val first = uses.first()
        return GameAction.UseItem(first.item, first.target, first.move, uses.drop(1))
    }

    private fun itemUse(json: JsonObject) = ItemUse(
        ItemRef(string(json, "item")),
        json["target"]?.jsonPrimitive?.contentOrNull?.let { monId(it) },
        json["move"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::MoveRef),
    )

    val saveGame = ActionDefinition(GameAction.SaveGame::class, spec(
        name = "save_game",
        description = "Save the game (start menu → SAVE). Safe to do often.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            locked(state, StartMenuFeature.SAVE) ?: if (PartyBagPlans.inField(state)) Availability.Available() else Availability.Hidden
        },
        parse = { GameAction.SaveGame },
    ))

    val chooseStarter = ActionDefinition(GameAction.ChooseStarter::class, spec(
        name = "choose_starter",
        description = "Take one of the starters on the professor's machine, for good: turns the machine to its ball, looks at it, " +
            "picks it and confirms (each step checked on the machine's state).",
        parameters = listOf(Parameter("starter", ParameterType.STRING, "The starter: its species id (species:155) or its name.")),
        modes = assisted,
        availability = { state ->
            val screen = state.screen as? Screen.StarterChoice
            if (screen == null) Availability.Hidden
            else Availability.Available(mapOf("starter" to screen.starters.map { Choice("species:${it.id.value}", it.name) }))
        },
        parse = { json -> GameAction.ChooseStarter(string(json, "starter")) },
    ))

    val softReset = ActionDefinition(GameAction.SoftReset::class, spec(
        name = "soft_reset",
        description = "Restart the game (L+R+START+SELECT) and continue the saved game: everything since the last save is LOST " +
            "(retry a lost battle, a failed capture...). Not while the game saves.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            when {
                state.screen is Screen.Intro -> Availability.Available(listed = false)
                // The game refuses the reset while it saves by itself (after the Hall of Fame).
                (state.screen as? Screen.Animation)?.kind == AnimationKind.SAVING -> Availability.Hidden
                else -> Availability.Available()
            }
        },
        parse = { GameAction.SoftReset },
    ))

    val continueGame = ActionDefinition(GameAction.ContinueGame::class, spec(
        name = "continue_game",
        description = "From the intro movie, the title screen or the main menu: go on to the main menu, pick CONTINUE and " +
            "wait until the saved game runs. Each screen is read before acting and each input checked (the title screen " +
            "ignores input for a moment after it appears: pressing then does nothing).",
        parameters = emptyList(),
        modes = assisted,
        availability = { state -> if (ActionConditions.beforeTheGame(state)) Availability.Available() else Availability.Hidden },
        parse = { GameAction.ContinueGame },
    ))

    val watchHallOfFame = ActionDefinition(GameAction.WatchHallOfFame::class, spec(
        name = "watch_hall_of_fame",
        description = "After beating the Champion: waits while the Hall of Fame presents each team member (progress " +
            "\"3/6 Pokémon presented, TYPHLOSION\"; presses during it are ignored by the game), presses A when the " +
            "whole team's screen waits for it (checked in RAM), waits while the game saves, and returns at the start " +
            "of the credits with what can be done there.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state -> if (ActionConditions.hallOfFameOffered(state)) Availability.Available() else Availability.Hidden },
        parse = { GameAction.WatchHallOfFame },
    ))

    private val moveParameters = listOf(
        Parameter("avoid_tall_grass", ParameterType.BOOLEAN, "Avoid tall grass when another way exists (fewer wild battles).", required = false),
        Parameter("avoid_trainers", ParameterType.BOOLEAN, "Avoid the line of sight of trainers when another way exists.", required = false),
        Parameter("accept_one_way", ParameterType.BOOLEAN, "Allow a way with no way back (ledges you can't come back up by any path). Ledges that are only shortcuts are always taken.", required = false),
        Parameter("run", ParameterType.BOOLEAN, "Run (hold B, with the running shoes): true by default; false walks everywhere.", required = false),
        Parameter(
            "run_in_encounter_areas", ParameterType.BOOLEAN,
            "Also run on the tiles where wild Pokémon can appear (tall grass, cave floors...). Off by default: walks walk there " +
                "when this game makes encounters more frequent running (with the running shoes switched on, the game always runs), " +
                "except while a Repel keeps them all away (until its repel_steps run out: walks count them).",
            required = false,
        ),
        Parameter("bike", ParameterType.BOOLEAN, "Ride the Bicycle (from the bag, or Y when registered) where cycling is allowed: faster.", required = false),
        Parameter(
            "on_repel_end", ParameterType.STRING,
            "When the Repel wears off on the way: stop (default: stops on that tile, INTERRUPTED repel_ended, you decide), continue " +
                "(walk on without one), reapply (use a Repel from the bag, then walk on; none left: stop) or auto (reapply when the rest " +
                "of the way crosses tiles where wild Pokémon appear and a Repel is left, else continue).",
            required = false, values = RepelEnd.entries.map { it.wire },
        ),
        Parameter(
            "on_avoid_detour", ParameterType.STRING,
            "With avoid_trainers / avoid_tall_grass, when the way around them is a detour (more than twice the steps of the shortest " +
                "way, and at least 30 more) while the short way goes through them: refuse (default: refused before moving, the error " +
                "gives both ways, you decide) or short_way (take the short way, each walk still avoiding them where it can; those in " +
                "the way may stop you).",
            required = false, values = AvoidDetour.entries.map { it.wire },
        ),
        Parameter(
            "on_local_detour", ParameterType.STRING,
            "When a target of the map you are on is reached only by a loop through many other maps (rocks, walls or heights in " +
                "between): refuse (default: refused before moving, the error gives the loop, you decide) or go (take the loop).",
            required = false, values = LocalDetour.entries.map { it.wire },
        ),
    )

    val goTo = ActionDefinition(GameAction.GoTo::class, spec(
        name = "go_to",
        description = "Walk to a tile (x, y), or to a target: person:N, item:N, sign:N, hidden_item:N (next to it), warp:N or " +
            "hole:N (goes through), cart:N / teleport:N of the puzzle (rides it), exit:north|south|east|west (into the neighbouring map that way), a map's name " +
            "(\"Route 26\", \"Victory Road 2F\": walks until entering it), or frontier (the nearest way out of this map " +
            "you are not standing at; stops before it). With map, x / y are on that map (another floor or a neighbour). " +
            "Goes through warps, stairs, holes and map edges when needed, however far the map asked for (a target of this map reachable only by a loop through many other maps is refused before moving, with the way, " +
            "unless on_local_detour go; so is a way around what avoid_trainers / avoid_tall_grass avoid that is more than twice the shortest way's steps, unless on_avoid_detour short_way; " +
            "a way the walk's own costs, wild Pokémon and trainers' battles, make that long is replaced by the shorter one, said so). Walks onto a scene trigger only when it is the " +
            "destination or the only way (and says so). Stops early when something happens (battle, trainer, phone call, script, " +
            "and the Repel wearing off: on_repel_end continue / reapply / auto walk on instead). " +
            "Runs by default, walking onto the tiles where wild Pokémon can appear (run, run_in_encounter_areas). " +
            "Uses field moves by itself when the party can (a Pokémon knows the move and the badge is owned; a fainted Pokémon " +
            "can still use its field moves outside battle): Surf from the shore, " +
            "Waterfall, Whirlpool, Cut, Rock Smash, Rock Climb (up and down rocky walls), Strength (boulders pushed as needed) and ice blocks; otherwise the error says " +
            "which move or badge is missing and the tile and direction to use it from. Movement puzzles (boulders, ice blocks, " +
            "moving platforms, lifts, carts and their levers) are solved by itself unless the state says movement_puzzles are left to you. " +
            "When the state says destinations are hidden, only places of the current map are accepted (its warps and exits " +
            "are taken; another map, by name or with map, is refused with DESTINATIONS_HIDDEN) and walks never go through other maps.",
        parameters = listOf(
            Parameter("x", ParameterType.INTEGER, "Tile x (with y).", required = false),
            Parameter("y", ParameterType.INTEGER, "Tile y (with x).", required = false),
            Parameter("target", ParameterType.STRING, "Instead of x / y: person:N, item:N, warp:N, hole:N, sign:N, hidden_item:N, cart:N, teleport:N, exit:<direction>, a map's name, or frontier.", required = false),
            Parameter("map", ParameterType.STRING, "The map x / y are on, when it isn't the current one (its name as exits show it, or map:<id>).", required = false),
        ) + moveParameters,
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available(mapOf("target" to targetChoices(state))) else Availability.Hidden },
        parse = { json ->
            val target = json["target"]?.jsonPrimitive?.contentOrNull
            val x = json["x"]?.jsonPrimitive?.intOrNull
            val y = json["y"]?.jsonPrimitive?.intOrNull
            val map = json["map"]?.jsonPrimitive?.contentOrNull
            if (target == null && (x == null || y == null) && map == null) throw ActionException(ActionError.InvalidParameter("target", "missing", listOf("x and y", "target", "map")))
            GameAction.GoTo(x, y, target, moveOptions(json), map)
        },
    ))

    val interact = ActionDefinition(GameAction.Interact::class, spec(
        name = "interact",
        description = "Walk next to a person, sign or item of this map, face it and press A (talk, read, pick up).",
        parameters = listOf(Parameter("target", ParameterType.STRING, "person:N, item:N (item ball), sign:N, hidden_item:N or examine:N (something invisible to examine).")),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available(mapOf("target" to targetChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.Interact(string(json, "target")) },
    ))

    val step = ActionDefinition(GameAction.Step::class, spec(
        name = "step",
        description = "Walk a few tiles straight in a direction (turning first if needed: no press is lost to the turn). " +
            "Stops early when the way is blocked (says where) or something happens (battle, trainer, script, the Repel wearing off: " +
            "see on_repel_end). " +
            "Runs by default, walking onto the tiles where wild Pokémon can appear (run, run_in_encounter_areas).",
        parameters = listOf(
            Parameter("direction", ParameterType.STRING, "north, south, west or east.", values = Direction.entries.map { it.name.lowercase() }),
            Parameter("tiles", ParameterType.INTEGER, "How many tiles (1-${MovePlans.MAX_STEP_TILES}, default 1).", required = false),
        ) + moveParameters,
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden },
        parse = { json ->
            val raw = string(json, "direction")
            val direction = Direction.parse(raw) ?: throw ActionException(ActionError.InvalidParameter("direction", raw, Direction.entries.map { it.name.lowercase() }))
            val tiles = json["tiles"]?.jsonPrimitive?.intOrNull ?: 1
            if (tiles !in 1..MovePlans.MAX_STEP_TILES) throw ActionException(ActionError.InvalidParameter("tiles", tiles.toString(), listOf("1..${MovePlans.MAX_STEP_TILES}")))
            GameAction.Step(direction, tiles, moveOptions(json))
        },
    ))

    val findEncounter = ActionDefinition(GameAction.FindEncounter::class, spec(
        name = "find_encounter",
        description = "Walk to the nearest place of this map where wild Pokémon appear (tall grass, a cave's floor; the water while surfing) and pace there until one appears.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden },
        parse = { GameAction.FindEncounter },
    ))

    val heal = ActionDefinition(GameAction.Heal::class, spec(
        name = "heal",
        description = "Heal the party at the nurse of this Pokémon Center (walks to the counter, answers YES).",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            when {
                !MovePlans.canWalk(state, hasWorld = true) -> Availability.Hidden
                state.field?.objects?.any { it.role == PersonRole.NURSE } != true -> Availability.Hidden
                else -> Availability.Available()
            }
        },
        parse = { GameAction.Heal },
    ))

    val deposit = ActionDefinition(GameAction.Deposit::class, spec(
        name = "deposit",
        description = "Deposit a party Pokémon in the PC of this building (walks to the PC; first box with room).",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state ->
            if (MovePlans.canWalk(state, hasWorld = true) && state.field?.hasPc != false && state.party.size > 1) Availability.Available(mapOf("pokemon" to monChoices(state)))
            else Availability.Hidden
        },
        parse = { json -> GameAction.Deposit(mon(json, "pokemon")) },
    ))

    val withdraw = ActionDefinition(GameAction.Withdraw::class, spec(
        name = "withdraw",
        description = "Take a Pokémon out of the PC of this building into the party (walks to the PC, finds its box).",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The stored Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state ->
            when {
                !MovePlans.canWalk(state, hasWorld = true) || state.field?.hasPc == false -> Availability.Hidden
                state.party.size >= 6 -> Availability.Unavailable(UnavailableReason.PARTY_FULL, "The party is full", "deposit one first, or swap them in one `pc` session")
                else -> Availability.Available(state.storage?.let { mapOf("pokemon" to storedChoices(it)) } ?: emptyMap())
            }
        },
        parse = { json -> GameAction.Withdraw(mon(json, "pokemon")) },
    ))

    val pc = ActionDefinition(GameAction.Pc::class, spec(
        name = "pc",
        description = "Several PC storage operations in one session at the PC of this building (it stays on in between), in order: " +
            "{\"op\":\"deposit\",\"pokemon\":\"mon:…\",\"box\":2?}, {\"op\":\"withdraw\",\"pokemon\":\"mon:…\"}, " +
            "{\"op\":\"move\",\"pokemon\":\"mon:…\",\"box\":3} (to another box), {\"op\":\"swap\",\"pokemon\":\"<party mon>\",\"with\":\"<stored mon>\"}. " +
            "Boxes are numbered from 1 like in the game.",
        parameters = listOf(Parameter("operations", ParameterType.ARRAY, "The operations, in order.", fields = listOf(
            Parameter("op", ParameterType.STRING, "What to do.", values = listOf("deposit", "withdraw", "move", "swap")),
            Parameter("pokemon", ParameterType.STRING, "The Pokémon (mon:…): for swap, the party one."),
            Parameter("box", ParameterType.INTEGER, "The box, from 1: where deposit puts it (optional) or move takes it.", required = false),
            Parameter("with", ParameterType.STRING, "For swap: the stored Pokémon (mon:…) that joins the party.", required = false),
        ))),
        modes = assisted,
        availability = { state ->
            if (!MovePlans.canWalk(state, hasWorld = true) || state.field?.hasPc == false) return@spec Availability.Hidden
            Availability.Available(buildMap {
                put("party", monChoices(state))
                state.storage?.let { put("stored", storedChoices(it)) }
            })
        },
        parse = { json -> GameAction.Pc(pcOperations(json)) },
    ))

    val buy = ActionDefinition(GameAction.Buy::class, spec(
        name = "buy",
        description = "Buy items at this Poké Mart in one visit to the counter (walks to the clerk who sells them, when a floor has " +
            "several; also works from the clerk's menu or the shop list): one `item` + `quantity`, or a list `items` of {item, quantity}. " +
            "Without any item nothing is bought and the answer's `detail` lists what each clerk sells (item id, name, price; it may talk " +
            "to a clerk to read the list). Prices are in the shop's currency (money; athlete points at the Pokéathlon Dome, where each line " +
            "is bought one at a time, quantity 1).",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The item: its id (item:4) or its name.", required = false),
            Parameter("quantity", ParameterType.INTEGER, "How many, 1 to 99 (default 1).", required = false),
            Parameter("items", ParameterType.ARRAY, "Several purchases in one visit: [{\"item\": \"item:23\", \"quantity\": 15}, ...].", required = false, fields = listOf(
                Parameter("item", ParameterType.STRING, "The item: its id (item:4) or its name."),
                Parameter("quantity", ParameterType.INTEGER, "How many, 1 to 99 (default 1).", required = false),
            )),
        ),
        modes = assisted,
        availability = { state ->
            if (ShopPlans.stage(state) == null) return@spec Availability.Hidden
            // Clerk by clerk (never one list mixing two counters): an item sold by several says by whom.
            val stock = ShopPlans.stock(state)
            // A line sold out is shown by the list but can't be bought: not offered.
            val sellers = stock.flatMap { s -> s.items.filter { !it.soldOut }.map { Triple(it, s.clerk, s.currency) } }.groupBy({ it.first.item.id }, { it })
            Availability.Available(if (sellers.isEmpty()) emptyMap() else mapOf("item" to sellers.values.map { lines ->
                val (item, _, currency) = lines.first()
                val by = lines.mapNotNull { it.second?.id }.takeIf { stock.size > 1 && it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")") ?: ""
                Choice("item:${item.item.id.value}", item.item.name + (item.price?.let { p -> " " + currency.format(p.toLong()) } ?: "") + by)
            }))
        },
        parse = { json -> GameAction.Buy(purchases(json)) },
    ))

    val setQuantity = ActionDefinition(GameAction.SetQuantity::class, spec(
        name = "set_quantity",
        description = "On a quantity screen (shop, bag...): set the number (each press is read back), and confirm it with A when `confirm` is true.",
        parameters = listOf(
            Parameter("value", ParameterType.INTEGER, "The number wanted."),
            Parameter("confirm", ParameterType.BOOLEAN, "Press A once the number is set.", required = false),
        ),
        modes = assisted,
        availability = { state ->
            val screen = state.screen as? Screen.Quantity ?: return@spec Availability.Hidden
            Availability.Available(mapOf("value" to listOf(Choice("${screen.min}..${screen.max}", "now ${screen.value}"))))
        },
        parse = { json -> GameAction.SetQuantity(int(json, "value", 0..999), bool(json, "confirm")) },
    ))

    val switch = ActionDefinition(GameAction.Switch::class, spec(
        name = "switch",
        description = "Send another Pokémon into battle (instead of attacking, or to replace a fainted one).",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state ->
            if (state.battle == null || !ActionConditions.canSwitch(state)) return@spec Availability.Hidden
            // A voluntary switch (command menu) is refused while the active Pokémon is trapped (Mean Look, Spider Web,
            // a binding move, Ingrain); a replacement after a K.O. never is.
            val actorRef = (state.screen as? Screen.BattleCommand)?.actor ?: state.battle.actor ?: BattlerRef.PLAYER_LEFT
            val trapped = state.battle.battlers.firstOrNull { it.ref == actorRef }?.volatile?.any { it is dev.kotlinds.pokemonclient.state.VolatileStatus.Trapped } == true
            if (state.screen is Screen.BattleCommand && trapped) {
                return@spec Availability.Unavailable(UnavailableReason.TRAPPED, "The active Pokémon is trapped: it can't be switched out", "a Shed Shell, Baton Pass or U-turn still work")
            }
            // Every Pokémon on the field (two in doubles) is already in battle.
            val active = state.battle.battlers.filter { it.ref.isPlayerSide && it.hp > 0 }.mapNotNull { it.mon }.toSet() + listOfNotNull(state.battle.partyOrder.firstOrNull())
            val choices = state.party.filter { it.id !in active && !it.fainted && !it.isEgg }.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level} ${it.hp}/${it.maxHp}") }
            if (choices.isEmpty()) Availability.Unavailable(UnavailableReason.NO_STOCK, "No other Pokémon can battle") else Availability.Available(mapOf("pokemon" to choices))
        },
        parse = { json -> GameAction.Switch(mon(json, "pokemon")) },
    ))

    val throwBall = ActionDefinition(GameAction.ThrowBall::class, spec(
        name = "throw_ball",
        description = "Throw a Poké Ball at the wild Pokémon (wild battles only). Says how it ended: caught (then answers the " +
            "nickname question and goes on until the battle is over), broke free after N shakes, or missed.",
        parameters = listOf(
            Parameter("ball", ParameterType.STRING, "The ball: its id (item:4) or its name."),
            Parameter("nickname", ParameterType.STRING, "Nickname to give if it's caught (omit: no nickname).", required = false),
        ),
        modes = assisted,
        availability = { state ->
            val battle = state.battle ?: return@spec Availability.Hidden
            if (state.screen !is Screen.BattleCommand) return@spec Availability.Hidden
            if (battle.trainers.isNotEmpty()) return@spec Availability.Unavailable(UnavailableReason.TRAINER_BATTLE, "You can't catch a trainer's Pokémon")
            val balls = state.bag.orEmpty().firstOrNull { it.name == "balls" }?.items.orEmpty()
            if (balls.isEmpty()) Availability.Unavailable(UnavailableReason.NO_STOCK, "No Poké Balls in the bag", "buy some at a Poké Mart")
            else Availability.Available(mapOf("ball" to balls.map { Choice("item:${it.item.id.value}", "${it.item.name} x${it.quantity}") }))
        },
        parse = { json -> GameAction.ThrowBall(ItemRef(string(json, "ball")), json["nickname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) },
    ))

    val learnMove = ActionDefinition(GameAction.LearnMove::class, spec(
        name = "learn_move",
        description = "A Pokémon wants to learn a new move but knows four: forget one of them, or keep the old moves (no `forget`).",
        parameters = listOf(Parameter("forget", ParameterType.STRING, "The move to forget (id or name); omit to keep the old moves.", required = false)),
        modes = assisted,
        availability = { state ->
            if (!ActionConditions.isLearnPrompt(state)) return@spec Availability.Hidden
            // On the list itself, the moves the game lets go of (not HMs, not the new one).
            val list = state.screen as? Screen.MoveSelect
            val choices = list?.entries?.filter { it.selectable && it.id.startsWith("move:") && it.id != "move:${list.newMove?.id?.value}" }
                ?.map { Choice(it.id, it.label) }
            Availability.Available(choices?.let { mapOf("forget" to it) } ?: emptyMap())
        },
        parse = { json -> GameAction.LearnMove(json["forget"]?.jsonPrimitive?.contentOrNull?.let(::MoveRef)) },
    ))

    val fish = ActionDefinition(GameAction.Fish::class, spec(
        name = "fish",
        description = "Cast a rod once towards the water you face (A is pressed exactly when something bites). Ends hooked (wild battle) or with nothing.",
        parameters = listOf(Parameter("rod", ParameterType.STRING, "The rod: its id or its name (Old Rod, Good Rod, Super Rod).")),
        modes = assisted,
        availability = { state ->
            if (!MovePlans.canWalk(state, hasWorld = true)) return@spec Availability.Hidden
            val rods = state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value in RODS }
            if (rods.isEmpty()) Availability.Hidden else Availability.Available(mapOf("rod" to rods.map { Choice("item:${it.item.id.value}", it.item.name) }))
        },
        parse = { json -> GameAction.Fish(ItemRef(string(json, "rod"))) },
    ))

    /** Old Rod, Good Rod, Super Rod (Gen 4 item ids). */
    private val RODS = setOf(445, 446, 447)

    val enterText = ActionDefinition(GameAction.EnterText::class, spec(
        name = "enter_text",
        description = "Type a name on the naming keyboard (nickname, box name...): replaces what is there, then OK.",
        parameters = listOf(Parameter("text", ParameterType.STRING, "The text to type.")),
        modes = assisted,
        availability = { state -> if (state.screen is Screen.Keyboard) Availability.Available() else Availability.Hidden },
        parse = { json -> GameAction.EnterText(json["text"]?.jsonPrimitive?.contentOrNull ?: throw ActionException(ActionError.InvalidParameter("text", "missing"))) },
    ))

    val teach = ActionDefinition(GameAction.Teach::class, spec(
        name = "teach",
        description = "Teach a TM / HM from the bag to a Pokémon (give `forget` when it already knows four moves).",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The machine: its id (item:328) or its name (TM01)."),
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…)."),
            Parameter("forget", ParameterType.STRING, "The move to forget when it knows four (id or name).", required = false),
        ),
        modes = assisted,
        availability = { state ->
            val machines = state.bag.orEmpty().firstOrNull { it.name == "tms_hms" }?.items.orEmpty()
            if (!PartyBagPlans.inField(state) || machines.isEmpty()) Availability.Hidden
            else Availability.Available(mapOf("item" to machines.map { Choice("item:${it.item.id.value}", it.item.name) }, "pokemon" to monChoices(state)))
        },
        parse = { json -> GameAction.Teach(ItemRef(string(json, "item")), mon(json, "pokemon"), json["forget"]?.jsonPrimitive?.contentOrNull?.let(::MoveRef)) },
    ))

    val useKeyItem = ActionDefinition(GameAction.UseKeyItem::class, spec(
        name = "use_key_item",
        description = "Use a key item from the bag (Bicycle, Dowsing Machine, a rod...). Keys that work by interacting with what " +
            "they open or wake (Basement Key, Card Key, SquirtBottle) have no USE: interact instead.",
        parameters = listOf(Parameter("item", ParameterType.STRING, "The key item: its id or its name.")),
        modes = assisted,
        availability = { state ->
            val keys = state.bag.orEmpty().firstOrNull { it.name == "key_items" }?.items.orEmpty()
            if (!PartyBagPlans.inField(state) || keys.isEmpty()) Availability.Hidden
            else Availability.Available(mapOf("item" to keys.map { Choice("item:${it.item.id.value}", it.item.name) }))
        },
        parse = { json -> GameAction.UseKeyItem(ItemRef(string(json, "item"))) },
    ))

    val registerItem = ActionDefinition(GameAction.RegisterItem::class, spec(
        name = "register_item",
        description = "Register a key item you use often (Bicycle, rod...): the first registered item is then used with Y, the second with its touch button, without the bag.",
        parameters = listOf(Parameter("item", ParameterType.STRING, "The key item: its id or its name.")),
        modes = assisted,
        availability = { state ->
            val keys = state.bag.orEmpty().firstOrNull { it.name == "key_items" }?.items.orEmpty()
            if (!PartyBagPlans.inField(state) || keys.isEmpty()) Availability.Hidden
            else Availability.Available(mapOf("item" to keys.map { Choice("item:${it.item.id.value}", it.item.name + if (state.registeredItems.firstOrNull() == it.item.id) " (on Y)" else "") }))
        },
        parse = { json -> GameAction.RegisterItem(ItemRef(string(json, "item"))) },
    ))

    val fly = ActionDefinition(GameAction.Fly::class, spec(
        name = "fly",
        description = "Fly to a town already visited (needs a Pokémon knowing Fly and its badge, on a map that allows it: listed as " +
            "unavailable where the map's own flag forbids it). In HGSS a town of the " +
            "other region (Johto / Kanto) is reached through Indigo Plateau by itself (two flights) once Indigo Plateau was visited.",
        parameters = listOf(Parameter("destination", ParameterType.STRING, "fly:<map id> as listed on the fly map, or the town's name.")),
        modes = assisted,
        availability = { state ->
            // The game's Fly rule (the move, the badge by id: never its shown name, the game may be in French).
            // Null (a state not read by its game, tests) can't tell: hidden like a game without Fly.
            val fly = state.fieldMoves?.get(FieldMoveKind.FLY)
            when {
                !MovePlans.canWalk(state, hasWorld = true) || fly == null || fly == FieldMoveAccess.Unknown -> Availability.Hidden
                fly == FieldMoveAccess.NotSupported -> Availability.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "Fly isn't supported in this game yet (its party menu isn't decoded)")
                state.field?.flyAllowed == false -> Availability.Unavailable(UnavailableReason.NOT_FLYABLE_HERE, "Fly can't be used on this map (the map doesn't allow it)", "go to a map where Fly works")
                fly == FieldMoveAccess.NoPokemon -> Availability.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon in the party knows Fly")
                fly is FieldMoveAccess.NoBadge -> Availability.Unavailable(UnavailableReason.NEEDS_BADGE, "Fly needs the ${fly.badge} Badge")
                else -> Availability.Available()
            }
        },
        parse = { json -> GameAction.Fly(string(json, "destination")) },
    ))

    val useFieldMove = ActionDefinition(GameAction.UseFieldMove::class, spec(
        name = "use_field_move",
        description = "Use a field move outside battle in one call (Teleport, Dig, Flash, Sweet Scent, Milk Drink / Softboiled...): " +
            "a Pokémon of the party that knows it is picked for you (read by move id), then party menu → the Pokémon → the move, " +
            "each menu checked. The game decides whether it works here: a refusal is an error with its message. Fly has its own " +
            "action (fly); Surf, Cut, Strength... are used by go_to by itself.",
        parameters = listOf(
            Parameter("move", ParameterType.STRING, "The field move, as listed (teleport, dig, flash, sweet_scent...).",
                values = FieldMoveKind.entries.filter { it.use == FieldMoveUse.ACTION }.map { it.wire }),
            Parameter("target", ParameterType.STRING, "Milk Drink / Softboiled: the Pokémon that gets the HP (mon:…).", required = false),
        ),
        modes = assisted,
        availability = { state ->
            if (!PartyBagPlans.inField(state)) return@spec Availability.Hidden
            // The game's rules as the state read them (GameState.fieldMoves: the move known, the badge): the moves of
            // this action only (Fly and the moves walks use have their own ways).
            val access = state.fieldMoves.orEmpty().filterKeys { it.use == FieldMoveUse.ACTION }
            val usable = access.filterValues { it is FieldMoveAccess.Usable }
            when {
                usable.isNotEmpty() -> Availability.Available(buildMap {
                    put("move", usable.map { (kind, a) -> Choice(kind.wire, "${with(FieldMoveWalk) { kind.label() }} (${(a as FieldMoveAccess.Usable).monName})") })
                    if (usable.keys.any { it.healsAnother }) put("target", monChoices(state))
                })
                access.values.any { it is FieldMoveAccess.NoBadge } -> access.entries.first { it.value is FieldMoveAccess.NoBadge }.let { (kind, a) ->
                    Availability.Unavailable(UnavailableReason.NEEDS_BADGE, "${with(FieldMoveWalk) { kind.label() }} needs the ${(a as FieldMoveAccess.NoBadge).badge} Badge")
                }
                access.values.any { it == FieldMoveAccess.NotSupported } ->
                    Availability.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "field moves aren't supported in this game yet (its party menu isn't decoded)")
                else -> Availability.Hidden
            }
        },
        parse = { json ->
            val raw = string(json, "move")
            val move = FieldMoveKind.parse(raw)?.takeIf { it.use == FieldMoveUse.ACTION }
                ?: throw ActionException(ActionError.InvalidParameter("move", raw, FieldMoveKind.entries.filter { it.use == FieldMoveUse.ACTION }.map { it.wire }))
            GameAction.UseFieldMove(move, json["target"]?.jsonPrimitive?.contentOrNull?.let { monId(it) })
        },
        // For models picking from a list: the usable moves that need no target (Milk Drink / Softboiled take one).
        enumerate = { state ->
            state.fieldMoves.orEmpty().filter { (kind, access) -> kind.use == FieldMoveUse.ACTION && !kind.healsAnother && access is FieldMoveAccess.Usable }
                .keys.map { GameAction.UseFieldMove(it) }
        },
    ))

    val setOptions = ActionDefinition(GameAction.SetOptions::class, spec(
        name = "set_options",
        description = "Set the game's OPTIONS (start menu → OPTIONS): text speed, battle scene (animations), battle style; then CONFIRM. " +
            "Omitted settings stay as they are.",
        parameters = listOf(
            Parameter("text_speed", ParameterType.STRING, "How fast messages print.", required = false, values = listOf("slow", "mid", "fast")),
            Parameter("battle_scene", ParameterType.STRING, "Battle animations.", required = false, values = listOf("on", "off")),
            Parameter(
                "battle_style", ParameterType.STRING,
                "shift: when you knock out a trainer's Pokémon, the game asks whether to switch before the foe sends its next one: " +
                    "a free switch (seeing what comes, no turn lost, no hit taken); answer KEEP BATTLING (keep_battling) to stay. " +
                    "set: no question (one call fewer per K.O.), but changing Pokémon then costs a turn and the foe's hit.",
                required = false, values = listOf("shift", "set"),
            ),
        ),
        modes = assisted,
        availability = { state ->
            locked(state, StartMenuFeature.OPTIONS)?.let { return@spec it }
            if (!PartyBagPlans.inField(state) && !ActionConditions.isOptionsScreen(state)) return@spec Availability.Hidden
            val now = state.options
            Availability.Available(now?.let {
                mapOf(
                    "text_speed" to listOf(Choice(it.textSpeed.name.lowercase(), "now")),
                    "battle_scene" to listOf(Choice(if (it.battleScene) "on" else "off", "now")),
                    "battle_style" to listOf(Choice(it.battleStyle.name.lowercase(), "now")),
                )
            } ?: emptyMap())
        },
        parse = { json ->
            fun value(key: String, allowed: List<String>): String? = json[key]?.jsonPrimitive?.contentOrNull?.lowercase()?.also {
                if (it !in allowed) throw ActionException(ActionError.InvalidParameter(key, it, allowed))
            }
            GameAction.SetOptions(
                textSpeed = value("text_speed", listOf("slow", "mid", "fast"))?.let(::textSpeed),
                battleScene = value("battle_scene", listOf("on", "off"))?.let { it == "on" },
                battleStyle = value("battle_style", listOf("shift", "set"))?.let(::battleStyle),
            )
        },
    ))

    /** Every common action, in the order they are listed to agents. */
    val definitions: List<ActionDefinition<*>> get() =
        listOf(advanceDialogue, choose, enterText, attack, switch, throwBall, learnMove, run, keepBattling, goTo, interact, step, findEncounter, heal, fly, useFieldMove, fish, buy, setQuantity, deposit, withdraw, pc, reorderParty, useItem, giveItem, takeItem, teach, useKeyItem, registerItem, saveGame, softReset, continueGame, watchHallOfFame, setOptions, chooseStarter, press, touch, wait) +
            MoreActions.definitions + PuzzleActions.definitions + PokegearActions.definitions

    // region Helpers

    /** The wire values of the options (`set_options`), by type. */
    private fun textSpeed(raw: String): TextSpeed? = TextSpeed.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    private fun battleStyle(raw: String): BattleStyle? = BattleStyle.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

    /** Walking around but the start menu has no [feature] yet: say so instead of failing on the menu. */
    private fun locked(state: GameState, feature: StartMenuFeature): Availability.Unavailable? {
        val walking = FieldControl.inControl(state)
        if (!walking || state.startMenu?.contains(feature) != false) return null
        val detail = if (StartMenuFeature.BAG !in state.startMenu) "The start menu doesn't open yet" else "The start menu has no ${feature.name.lowercase()} yet"
        return Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, detail, "the story unlocks it (Mom gives it at the start)")
    }

    private fun <A : GameAction> spec(
        name: String,
        description: String,
        parameters: List<Parameter>,
        modes: Set<ActionMode>,
        availability: (GameState) -> Availability,
        parse: (JsonObject) -> A,
        enumerate: (GameState) -> List<A> = { emptyList() },
    ): ActionSpec<A> = object : ActionSpec<A> {
        override val name = name
        override val description = description
        override val parameters = parameters
        override val modes = modes
        override fun availability(state: GameState) = availability(state)
        override fun parse(json: JsonObject) = parse(json)
        override fun enumerate(state: GameState) = enumerate(state)
    }

    private fun string(json: JsonObject, key: String): String =
        json[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ActionException(ActionError.InvalidParameter(key, "missing"))

    private fun int(json: JsonObject, key: String, range: IntRange): Int {
        val value = json[key]?.jsonPrimitive?.intOrNull ?: throw ActionException(ActionError.InvalidParameter(key, "missing"))
        return value.also { check(it, key, range) }
    }

    private fun check(value: Int, key: String, range: IntRange) {
        if (value !in range) throw ActionException(ActionError.InvalidParameter(key, value.toString(), listOf("${range.first}..${range.last}")))
    }

    private fun targetChoices(state: GameState) = state.field?.objects.orEmpty()
        .filter { it.kind != FieldObjectKind.FOLLOWER }
        .map { Choice(MovePlans.objectTargetId(it), "${it.label} at ${it.x},${it.y}") }

    /** Boolean parameter [key] of [json], [default] when absent or not a boolean. */
    private fun bool(json: JsonObject, key: String, default: Boolean = false) = json[key]?.jsonPrimitive?.booleanOrNull ?: default

    /** `item` + `quantity`, then every {item, quantity} of `items` (empty when nothing is named: the plan lists the shop). */
    private fun purchases(json: JsonObject): List<Purchase> {
        fun one(obj: JsonObject): Purchase? {
            val item = obj["item"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val quantity = obj["quantity"]?.jsonPrimitive?.intOrNull?.also { check(it, "quantity", 1..99) } ?: 1
            return Purchase(ItemRef(item), quantity)
        }
        val list = (json["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { element ->
            val obj = element as? JsonObject ?: throw ActionException(ActionError.InvalidParameter("items", element.toString(), listOf("{\"item\": \"item:23\", \"quantity\": 15}")))
            one(obj) ?: throw ActionException(ActionError.InvalidParameter("items", element.toString(), listOf("each line needs an item")))
        }
        return listOfNotNull(one(json)) + list
    }

    private fun moveOptions(json: JsonObject) = MoveOptions(
        avoidTallGrass = bool(json, "avoid_tall_grass"),
        avoidTrainers = bool(json, "avoid_trainers"),
        acceptOneWay = bool(json, "accept_one_way"),
        run = bool(json, "run", default = true),
        runInEncounterAreas = bool(json, "run_in_encounter_areas"),
        bike = bool(json, "bike"),
        onRepelEnd = wireValue(json, "on_repel_end", RepelEnd.entries, RepelEnd::wire) ?: RepelEnd.STOP,
        onAvoidDetour = wireValue(json, "on_avoid_detour", AvoidDetour.entries, AvoidDetour::wire) ?: AvoidDetour.REFUSE,
        onLocalDetour = wireValue(json, "on_local_detour", LocalDetour.entries, LocalDetour::wire) ?: LocalDetour.REFUSE,
    )

    /**
     * The value of string parameter [key] of [json] among [entries] by its [wire] form, null when absent; any other
     * value is refused with the allowed ones (never aliased).
     */
    private fun <E> wireValue(json: JsonObject, key: String, entries: List<E>, wire: (E) -> String): E? =
        json[key]?.jsonPrimitive?.contentOrNull?.let { raw ->
            entries.firstOrNull { wire(it) == raw } ?: throw ActionException(ActionError.InvalidParameter(key, raw, entries.map(wire)))
        }

    /** Stored Pokémon as choices: "mon:… = HO-OH Lv45 (BOX 1)". */
    private fun storedChoices(storage: dev.kotlinds.pokemonclient.state.PcStorage) = storage.boxes.flatMap { box ->
        box.mons.map { Choice(it.id.toString(), "${it.displayName}${it.level?.let { l -> " Lv$l" } ?: ""} (${box.name})") }
    }

    /** The `operations` of a `pc` action (boxes numbered from 1 on the wire, from 0 inside). */
    private fun pcOperations(json: JsonObject): List<PcOperation> {
        val ops = json["operations"] as? kotlinx.serialization.json.JsonArray
            ?: throw ActionException(ActionError.InvalidParameter("operations", "missing", listOf("[{\"op\":\"deposit\",\"pokemon\":\"mon:…\"}, …]")))
        return ops.map { element ->
            val obj = element as? JsonObject ?: throw ActionException(ActionError.InvalidParameter("operations", element.toString()))
            val box = obj["box"]?.jsonPrimitive?.intOrNull?.also { check(it, "box", 1..18) }?.minus(1)
            when (val op = obj["op"]?.jsonPrimitive?.contentOrNull) {
                "deposit" -> PcOperation.Deposit(mon(obj, "pokemon"), box)
                "withdraw" -> PcOperation.Withdraw(mon(obj, "pokemon"))
                "move" -> PcOperation.Move(mon(obj, "pokemon"), box ?: throw ActionException(ActionError.InvalidParameter("box", "missing", listOf("1..18"))))
                "swap" -> PcOperation.Swap(mon(obj, "pokemon"), mon(obj, "with"))
                else -> throw ActionException(ActionError.InvalidParameter("op", op ?: "missing", listOf("deposit", "withdraw", "move", "swap")))
            }
        }
    }

    /** `reorder_party` from JSON: one Pokémon and its position, or the whole `order` (a JSON array, or a string of one). */
    private fun parseReorderParty(json: JsonObject): GameAction.ReorderParty {
        val order = when (val raw = json["order"]) {
            null, is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonArray -> raw
            is kotlinx.serialization.json.JsonPrimitive -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw.content) as kotlinx.serialization.json.JsonArray }
                .getOrElse { throw ActionException(ActionError.InvalidParameter("order", raw.content, listOf("[\"mon:…\", \"mon:…\", …]"))) }
            else -> throw ActionException(ActionError.InvalidParameter("order", raw.toString()))
        } ?: return GameAction.ReorderParty(mon(json, "pokemon"), int(json, "position", 1..6))
        val ids = order.map { element ->
            val raw = (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: throw ActionException(ActionError.InvalidParameter("order", element.toString()))
            MonId.parse(raw) ?: throw ActionException(ActionError.InvalidParameter("order", raw))
        }
        if (ids.isEmpty() || ids.size > 6 || ids.toSet().size != ids.size) throw ActionException(ActionError.InvalidParameter("order", order.toString(), listOf("1 to 6 different mon:… ids")))
        return GameAction.ReorderParty(ids.first(), 1, ids)
    }

    private fun monChoices(state: GameState) = state.party.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level}") }

    private fun monId(raw: String): MonId = MonId.parse(raw) ?: throw ActionException(ActionError.InvalidParameter("pokemon", raw))

    private fun mon(json: JsonObject, key: String): MonId = monId(string(json, key))

    private fun button(json: JsonObject): Button {
        val raw = string(json, "button")
        return Button.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw ActionException(ActionError.InvalidParameter("button", raw, Button.entries.map { it.name.lowercase() }))
    }

    private fun battler(raw: String): BattlerRef = BattlerRef.entries.firstOrNull { it.wire == raw }
        ?: throw ActionException(ActionError.InvalidParameter("target", raw, BattlerRef.entries.map { it.wire }))

    // endregion
}
