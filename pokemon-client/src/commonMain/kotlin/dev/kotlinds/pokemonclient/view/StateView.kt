package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MajorStatus
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PokegearRadio
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The game state as agents read it: compact JSON built from the typed [GameState], with stable ids everywhere
 * (`mon:…`, `move:…`, `item:…`, entry ids) so an agent can name things in its actions.
 */
object StateView {

    /**
     * One line for a person watching (the app's panel, a decision history): the screen kind and, in the field, the map
     * and position, e.g. "overworld · New Bark Town (12, 8) facing west". Same for every game.
     */
    fun summary(state: GameState): String = buildString {
        append(state.screen.kind)
        state.field?.let { f ->
            append(" · ${f.mapName} (${f.x}, ${f.y})")
            f.facing?.let { append(" facing ${it.name.lowercase()}") }
        }
    }

    /** The screen: what the game waits for, the menu entries with the highlighted one, or the text shown. */
    fun screen(screen: Screen): JsonObject = buildJsonObject {
        put("kind", screen.kind)
        put("awaiting", screen.awaiting.name.lowercase())
        when (screen) {
            is Screen.Selectable -> {
                val highlighted = (screen.cursor as? Cursor.At)?.let { screen.entries.getOrNull(it.index) }
                put("highlighted", highlighted?.id ?: "none (the first D-pad press only shows the cursor)")
                putJsonArray("entries") {
                    screen.entries.forEach { e ->
                        add(buildJsonObject {
                            put("id", e.id)
                            put("label", e.label)
                            if (!e.selectable) put("selectable", false)
                            if (e.dangerous) put("dangerous", true)
                            e.touch?.let { put("touch", "${it.x},${it.y}") }
                        })
                    }
                }
                when (screen) {
                    is Screen.YesNo -> {
                        screen.question?.let { put("question", it) }
                        screen.learning?.let { offer ->
                            put("learning", buildJsonObject {
                                offer.mon?.let { put("pokemon", it.toString()) }
                                offer.monName?.let { put("name", it) }
                                put("new_move", "move:${offer.move.id.value} ${offer.move.name}")
                            })
                        }
                    }
                    is Screen.MoveSelect -> screen.newMove?.let { put("new_move", "move:${it.id.value} ${it.name}") }
                    is Screen.Keyboard -> {
                        put("typed", screen.buffer)
                        put("max_length", screen.maxLength)
                        put("page", screen.page)
                    }
                    is Screen.Bag -> {
                        put("pocket", screen.pocket)
                        put("page", "${screen.page + 1}/${screen.pages}")
                    }
                    is Screen.Shop -> {
                        // What the prices are paid with and how much of it the player has (money, athlete points...).
                        put("balance", screen.balance)
                        put("currency", screen.currency.name.lowercase())
                        if (screen.goods != dev.kotlinds.pokemonclient.state.ShopGoods.ITEMS) put("goods", screen.goods.name.lowercase())
                        if (screen.oneOfEach) put("one_of_each", "no quantity: each line is bought one at a time, then sold out")
                    }
                    is Screen.BattleCommand -> screen.actor?.let { put("choosing", it.wire) }
                    else -> Unit
                }
            }
            is Screen.Dialogue -> {
                put("source", screen.source.name.lowercase())
                screen.speaker?.let { put("speaker", it) }
                put("text", screen.text)
            }
            is Screen.PressToContinue -> {
                put("reason", screen.reason.name.lowercase())
                screen.text?.let { put("text", it) }
                if (screen.reason == ContinueReason.COMMUNICATION_ERROR) {
                    put("hint", "a wireless communication error stopped the game: A restarts it at the title screen. On the main " +
                        "menu with a save, it comes from an emulator core without wireless (melonDS): the saved game can't be continued there")
                }
            }
            is Screen.Quantity -> {
                put("value", screen.value)
                put("min", screen.min)
                put("max", screen.max)
            }
            is Screen.Evolution -> {
                put("from", screen.from.name)
                screen.to?.let { put("to", it.name) }
                screen.text?.let { put("text", it) }
                if (screen.canCancel) put("can_cancel", "B now stops the evolution")
            }
            is Screen.StarterChoice -> {
                put("starters", JsonArray(screen.starters.map { JsonPrimitive("species:${it.id.value} = ${it.name}") }))
                screen.starters.getOrNull(screen.front)?.let { put("in_front", "species:${it.id.value} = ${it.name}") }
                put("stage", screen.stage.name.lowercase())
                put("hint", "use choose_starter with the species id: it turns the machine, looks, picks and confirms")
            }
            is Screen.Overworld -> {
                screen.banner?.let { put("sign", it) }
                screen.incomingCall?.let { call ->
                    put("incoming_call", buildJsonObject {
                        put("caller", call.caller)
                        put("id", call.callerId)
                        put("hint", "the phone rings: advance_dialogue answers and reads the call; walking on ignores it")
                    })
                }
            }
            is Screen.Viewer -> {
                screen.exit.button?.let { put("exit", "press ${it.name.lowercase()}") }
                screen.exit.touch?.let { put("exit", "touch ${it.x},${it.y}") }
                if (screen.details.isNotEmpty()) put("details", JsonArray(screen.details.map(::JsonPrimitive)))
                screen.radio?.let { put("radio", radio(it)) }
                if (screen.app == ViewerApp.HALL_OF_FAME_REGISTER) {
                    put("hint", "watch_hall_of_fame waits through the presentation (presses are ignored meanwhile), presses A when the whole team waits for it and returns when the credits start")
                }
                if (screen.apps.isNotEmpty()) {
                    put("apps", JsonArray(screen.apps.map { a ->
                        JsonPrimitive("${a.id} = ${a.label}" + (a.touch?.let { " (touch ${it.x},${it.y})" } ?: "") + if (a.selectable) "" else " (locked)")
                    }))
                }
            }
            is Screen.Animation -> screen.hint?.let { put("hint", it) }
            is Screen.Unknown -> screen.hint?.let { put("hint", it) }
            is Screen.Intro -> intro(screen)
            else -> Unit
        }
    }

    /**
     * An intro screen: its stage, the inputs that pass it now (any one of them; none while it ignores input) and the
     * action that goes through to the saved game.
     */
    private fun JsonObjectBuilder.intro(screen: Screen.Intro) {
        put("detail", screen.detail)
        screen.goesOnWith?.let { inputs ->
            put("goes_on_with", JsonArray(
                inputs.buttons.sortedBy { it.ordinal }.map { JsonPrimitive("press ${it.name.lowercase()}") } +
                    if (inputs.touchAnywhere) listOf(JsonPrimitive("touch anywhere on the bottom screen")) else emptyList(),
            ))
        }
        val hint = when (screen.stage) {
            IntroStage.INTRO_MOVIE, IntroStage.LOADING, IntroStage.MAIN_MENU ->
                "continue_game goes on to CONTINUE and into the saved game, checking each screen"
            IntroStage.TITLE_SCREEN ->
                (if (screen.goesOnWith == null) "it ignores input for a moment; " else "") +
                    "continue_game goes on to CONTINUE and into the saved game, checking each screen (left alone, the title screen plays the intro movie again)"
            IntroStage.NEW_GAME_INTRO -> null
        }
        hint?.let { put("hint", it) }
    }

    /**
     * The Pokégear radio: band, what is tuned and airs, the dial's channels with the point to touch for each (ids are
     * the language-independent [RadioStation.wire] ids `tune_radio` takes).
     */
    fun radio(radio: PokegearRadio): JsonObject = buildJsonObject {
        put("band", radio.band.name.lowercase())
        val tuned = radio.tuned?.let { c -> radio.channels.firstOrNull { it.index == c } }
        put("tuned", when {
            tuned != null -> "channel ${tuned.index} (" + (if (radio.clear) "clear" else "static") + ")"
            radio.channels.isEmpty() -> "no signal here"
            else -> "between channels: nothing"
        })
        radio.station?.let { put("station", it.wire) }
        radio.title?.let { put("title", it) }
        radio.host?.let { put("host", it) }
        radio.line?.let { put("text", it) }
        radio.playing?.let { put("music_playing", it.wire) }
        put("cursor", "${radio.cursor.x},${radio.cursor.y}" + if (radio.inAppBar) " (the app bar has the cursor)" else "")
        put("channels", JsonArray(radio.channels.map { c ->
            JsonPrimitive("channel ${c.index}: ${c.stations.joinToString(" / ") { it.wire }} at ${c.touch.x},${c.touch.y} (clear within ${c.clearRadius} px)")
        }))
        put("hint", "tune_radio station:<id> tunes the dial (presets for channels 0-3, a drag of the cursor otherwise)")
    }

    /** One party Pokémon on one line-ish object. */
    fun mon(mon: PartyMon): JsonObject = buildJsonObject {
        put("id", mon.id.toString())
        put("name", mon.displayName)
        if (mon.nickname != null) put("species", mon.species.name)
        if (mon.isEgg) return@buildJsonObject
        put("level", mon.level)
        put("hp", "${mon.hp}/${mon.maxHp}")
        mon.status?.let { put("status", status(it)) }
        put("types", mon.types.joinToString("/"))
        mon.heldItem?.let { put("held_item", it.name) }
        put("moves", JsonArray(mon.moves.map { JsonPrimitive("${it.move.name} ${it.pp}/${it.maxPp}") }))
        put("exp", mon.exp)
        mon.expToNextLevel?.let { put("exp_to_next_level", it) }
    }

    fun battle(battle: BattleState): JsonObject = buildJsonObject {
        put("kind", battle.kind.name.lowercase())
        if (battle.isDouble) put("double", true)
        battle.trainers.takeIf { it.isNotEmpty() }?.let { put("trainers", JsonArray(it.map(::JsonPrimitive))) }
        // Weather changes what moves do (Solar Beam without its charging turn in the sun...): said while there is one.
        battle.weather?.takeIf { it.kind != dev.kotlinds.pokemonclient.state.WeatherKind.CLEAR }?.let { put("weather", it.describe()) }
        putJsonArray("battlers") { battle.battlers.forEach { add(battler(it)) } }
        battle.message?.let { put("message", it) }
    }

    private fun battler(b: BattlerState): JsonObject = buildJsonObject {
        put("position", b.ref.wire)
        b.mon?.let { put("id", it.toString()) }
        put("name", b.nickname ?: b.species.name)
        put("level", b.level)
        put("hp", "${b.hp}/${b.maxHp}")
        b.status?.let { put("status", status(it)) }
        if (b.volatile.isNotEmpty()) put("volatile", JsonArray(b.volatile.map { JsonPrimitive(volatile(it)) }))
        if (b.statStages.isNotEmpty()) putJsonObject("stat_stages") { b.statStages.forEach { (stat, v) -> put(stat.name.lowercase(), v) } }
        put("types", b.types.joinToString("/"))
        b.catchRate?.let { put("catch_rate", it) }
        if (b.ref.isPlayerSide) put("moves", JsonArray(b.moves.map { JsonPrimitive("move:${it.move.id.value} ${it.move.name} (${battleData(it)}) ${it.pp}/${it.maxPp}") }))
    }

    /**
     * The full compact state: screen, party, battle, position, money and badges, warnings. [showHidden]: show what the
     * game hides or the player hasn't seen (a puzzle's hidden switches, teleports never on screen), for agents allowed a
     * walkthrough; without it, [sightings] (what was seen so far) decides which teleports are listed.
     * [hideDestinations] (`ActionSettings.hideDestinations`): says so in the field ([DESTINATIONS_HIDDEN]), so the
     * agent understands the exits leading to "unknown" and the refusals of go_to to other maps.
     */
    fun state(state: GameState, showHidden: Boolean = true, sightings: Sightings? = null, hideDestinations: Boolean = false): JsonObject = buildJsonObject {
        put("screen", screen(state.screen))
        state.battle?.let { put("battle", battle(it)) }
        state.field?.let { f ->
            putJsonObject("position") {
                put("map", f.mapName.toString())
                put("x", f.x)
                put("y", f.y)
                f.facing?.let { put("facing", it.name.lowercase()) }
                put("movement", f.movement.name.lowercase())
                put("height", f.height)
            }
            if (hideDestinations) put("destinations", DESTINATIONS_HIDDEN)
            f.puzzle?.let { p ->
                val shown = if (showHidden) p else p.copy(teleports = p.teleports.filter { t -> sightings?.seen(f, t) ?: t.from.any { Sightings.onScreen(f, it) } })
                put("puzzle", puzzle(shown, showHidden))
            }
        }
        if (state.party.isNotEmpty()) put("team", buildJsonArray { state.party.forEach { add(mon(it)) } })
        state.player?.let { p ->
            put("money", p.money)
            put("badges", JsonArray(p.badges.map(::JsonPrimitive)))
            p.playTime?.let { put("play_time", it.toString()) }
            if (p.momParcels.isNotEmpty()) {
                put("mom_parcels", "Mom bought " + p.momParcels.joinToString { "${it.item.name} x${it.quantity}" } +
                    " for you: the delivery man standing in any Poké Mart hands it (talk to him, one parcel per visit)")
            }
        }
        state.field?.radioMusic?.let { put("radio_music", "${it.wire} (the Pokégear radio keeps playing it)") }
        // The Repel's counter as the game keeps it (one step per tile moved, walking, running, cycling or surfing alike;
        // checked on the bench): without it the agent could only guess when it wears off (NOTES: "Max Repel stops
        // after ~100-150 steps", which was the agent's own count of the tiles of its trips).
        state.field?.repelSteps?.takeIf { it > 0 }?.let { put("repel_steps", it) }
        if (state.warnings.isNotEmpty()) put("warnings", JsonArray(state.warnings.map { JsonPrimitive(it.detail) }))
    }

    /**
     * What the agent reads when destinations are hidden (`ActionSettings.hideDestinations`): why exits lead to
     * "unknown", what go_to still does, and that exploring and remembering is its own job.
     */
    const val DESTINATIONS_HIDDEN = "hidden: warps, holes and map edges are listed without where they lead (→ unknown), " +
        "and go_to only reaches places of the map you are on (its exits included: go_to warp:N / exit:<direction> takes it). " +
        "Explore: take exits to see where they lead, read signs and listen to people, and keep your own notes " +
        "(nothing is remembered for you)"

    /**
     * The PC boxes, compact: one line per non-empty box ("BOX 1 (7/30): mon:… HOOTHOOT Lv4, …"), the box the PC
     * opens on, and how many boxes are empty.
     */
    fun storage(storage: dev.kotlinds.pokemonclient.state.PcStorage): JsonObject = buildJsonObject {
        put("current_box", storage.currentBox + 1)
        putJsonArray("boxes") {
            storage.boxes.filter { it.mons.isNotEmpty() }.forEach { box ->
                add(JsonPrimitive("${box.name} (${box.mons.size}/${box.capacity}): " + box.mons.joinToString { m ->
                    "${m.id} ${m.displayName}" + (if (m.nickname != null) " (${m.species.name})" else "") + (m.level?.let { " Lv$it" } ?: "") +
                        (m.heldItem?.let { " @${it.name}" } ?: "")
                }))
            }
        }
        val empty = storage.boxes.count { it.mons.isEmpty() }
        if (empty > 0) put("empty_boxes", empty)
    }

    /**
     * The bag, compact: one line per non-empty pocket ("medicine": "item:17 Potion x3, item:26 Super Potion x1").
     */
    fun bag(pockets: List<dev.kotlinds.pokemonclient.state.BagPocket>): JsonObject = buildJsonObject {
        pockets.filter { it.items.isNotEmpty() }.forEach { pocket ->
            put(pocket.name, pocket.items.joinToString { "item:${it.item.id.value} ${it.item.name} x${it.quantity}" })
        }
    }

    /** The game's OPTIONS. */
    fun options(options: dev.kotlinds.pokemonclient.state.GameOptions): JsonObject = buildJsonObject {
        put("text_speed", options.textSpeed.name.lowercase())
        put("battle_scene", if (options.battleScene) "on" else "off")
        put("battle_style", options.battleStyle.name.lowercase())
    }

    /**
     * The map puzzle: its rule, switches (what they toggle), shutters (open or not, tiles), teleports, what can be seen
     * (lift floor, candles...) and the mechanism routes don't model. Hidden switches only with [showHidden].
     */
    fun puzzle(puzzle: PuzzleState, showHidden: Boolean = true): JsonObject = buildJsonObject {
        fun tiles(tiles: List<PuzzleTile>) = JsonArray(tiles.map { JsonPrimitive("${it.x},${it.y}") })
        put("kind", puzzle.kind.name.lowercase())
        // What only a walkthrough shows (shown with [showHidden] only): the puzzle's rule says how to read it only then.
        put("rule", puzzle.walkthroughRule?.takeIf { showHidden }?.let { "${puzzle.rule} $it" } ?: puzzle.rule)
        val switches = puzzle.switches.filter { showHidden || !it.hidden }
        if (switches.isNotEmpty()) putJsonArray("switches") {
            switches.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id)
                    put("interact", JsonArray(s.targets.map(::JsonPrimitive)))
                    put("at", tiles(s.tiles))
                    s.flipped?.let { put("flipped", it) }
                    if (s.toggles.isNotEmpty()) put("toggles", JsonArray(s.toggles.map(::JsonPrimitive)))
                    if (s.oneShot) put("one_shot", if (s.used) "used" else "unused")
                    if (s.hidden) put("hidden", "not visible in the game (walkthrough knowledge)")
                })
            }
        }
        if (puzzle.barriers.isNotEmpty()) putJsonArray("shutters") {
            puzzle.barriers.forEach { b ->
                add(buildJsonObject {
                    put("id", b.id)
                    put("open", b.open)
                    put("tiles", tiles(b.tiles))
                })
            }
        }
        if (puzzle.teleports.isNotEmpty()) putJsonArray("teleports") {
            puzzle.teleports.forEach { t ->
                add(buildJsonObject {
                    put("id", t.id)
                    put("kind", t.kind.name.lowercase())
                    put("from", tiles(t.from))
                    put("to", "${t.to.x},${t.to.y}")
                    t.toHeight?.let { put("to_height", it) }
                })
            }
        }
        if (puzzle.indicators.isNotEmpty()) putJsonArray("indicators") {
            puzzle.indicators.forEach { i ->
                add(buildJsonObject {
                    put("id", i.id)
                    put("on", i.on)
                    put("on_means", i.meaning)
                    put("at", tiles(i.tiles))
                })
            }
        }
        puzzle.unmodeled?.let { put("unmodeled", it) }
        if (puzzle.boulderHoles.isNotEmpty()) putJsonArray("boulder_holes") {
            puzzle.boulderHoles.forEach { b ->
                add(JsonPrimitive("${b.boulder} → hole at ${b.hole.x},${b.hole.y}: " + if (b.fallen) "fallen (on the floor below)" else "still to push in"))
            }
        }
        if (puzzle.iceBlocks.isNotEmpty()) putJsonArray("ice_blocks") {
            puzzle.iceBlocks.forEach { b ->
                add(JsonPrimitive("${b.block} at ${b.at.x},${b.at.y}: " + if (b.movable) "can be pushed" else "frozen against another block (can't move)"))
            }
        }
        // Which way each trainer steps is read in the gym's script: walkthrough knowledge ([showHidden] only).
        if (showHidden && puzzle.stepAside.isNotEmpty()) putJsonArray("step_aside") {
            puzzle.stepAside.forEach { t ->
                val ways = t.steps.groupBy({ it.steps }, { it.playerFacing })
                val how = if (ways.size == 1) "steps ${ways.keys.single().name.lowercase()}"
                else ways.entries.joinToString("; ") { (step, facing) -> "steps ${step.name.lowercase()} when talked to facing ${facing.joinToString("/") { it.name.lowercase() }}" }
                add(JsonPrimitive("${t.person}: " + if (t.beaten) "beaten (stays where it is)" else "once beaten, $how"))
            }
        }
        if (puzzle.herds.isNotEmpty()) putJsonArray("herds") {
            puzzle.herds.forEach { h ->
                add(buildJsonObject {
                    put("id", h.id)
                    put("at", "${h.at.x},${h.at.y}")
                    h.facing?.let { put("facing", it.name.lowercase()) }
                    // How to catch it is the agent's to work out: the blind spot and the plan are a walkthrough's.
                    if (showHidden) put("blind_spot", h.blindSpot)
                    putJsonArray("twigs") {
                        h.twigs.forEach { t -> add(buildJsonObject { put("id", t.id); put("tiles", tiles(t.tiles)); put("active", t.active) }) }
                    }
                    if (showHidden) putJsonArray("plan") {
                        h.plan.forEach { step ->
                            add(when (step) {
                                is dev.kotlinds.pokemonclient.state.HerdStep.StepOnTwig -> JsonPrimitive("step on ${step.twig} at ${step.tile.x},${step.tile.y}")
                                is dev.kotlinds.pokemonclient.state.HerdStep.TalkFrom -> JsonPrimitive(
                                    "from ${step.tile.x},${step.tile.y} face ${step.facing.name.lowercase()} and press A: " + when (val o = step.outcome) {
                                        is dev.kotlinds.pokemonclient.state.HerdOutcome.Flees -> "it runs to ${o.to.x},${o.to.y}"
                                        dev.kotlinds.pokemonclient.state.HerdOutcome.Caught -> "caught"
                                    },
                                )
                            })
                        }
                    }
                })
            }
        }
        if (puzzle.platforms.isNotEmpty()) putJsonArray("platforms") {
            puzzle.platforms.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id)
                    put("pivot", "${p.pivot.x},${p.pivot.y}")
                    put("rotation", p.rotation)
                    put("tiles", tiles(p.tiles))
                    putJsonArray("triggers") {
                        p.triggers.forEach { t ->
                            add(buildJsonObject {
                                put("at", "${t.tile.x},${t.tile.y}")
                                put("effect", when (val e = t.effect) {
                                    dev.kotlinds.pokemonclient.state.PlatformEffect.RotateClockwise -> "rotate_clockwise"
                                    is dev.kotlinds.pokemonclient.state.PlatformEffect.Slide -> "slide_${e.direction.name.lowercase()}_${e.tiles}"
                                })
                                put("possible_now", t.possible)
                            })
                        }
                    }
                })
            }
        }
    }

    /**
     * A move's battle data, compact: "Electric, special, power 40, accuracy 100%" (+ "priority +1" when not 0);
     * "never misses" for an accuracy of 0, no power for status moves and variable damage.
     */
    internal fun battleData(move: dev.kotlinds.pokemonclient.state.KnownMove): String = buildList {
        add(move.type ?: "?")
        move.category?.let { add(it.name.lowercase()) }
        move.power?.takeIf { it > 0 }?.let { add("power $it") }
        move.accuracy?.let { add(if (it == 0) "never misses" else "accuracy $it%") }
        move.priority?.takeIf { it != 0 }?.let { add("priority ${if (it > 0) "+$it" else "$it"}") }
    }.joinToString(", ")

    private fun status(status: MajorStatus) = when (status) {
        is MajorStatus.Asleep -> "asleep(${status.turns})"
        // In battle, the toxic counter: this turn's damage was counter/16 of the max HP, the next one (counter+1)/16.
        is MajorStatus.BadlyPoisoned -> if (status.counter > 0) "badly poisoned(${status.counter}, next ${status.counter + 1}/16 HP)" else "badly poisoned"
        MajorStatus.Burned -> "burned"
        MajorStatus.Frozen -> "frozen"
        MajorStatus.Paralyzed -> "paralyzed"
        MajorStatus.Poisoned -> "poisoned"
    }

    private fun volatile(status: VolatileStatus): String = when (status) {
        is VolatileStatus.Confused -> "confused(${status.turns})"
        is VolatileStatus.Infatuated -> "infatuated" + (status.with?.let { "(with ${it.wire})" } ?: "")
        is VolatileStatus.Bound -> "bound(${status.turns})"
        is VolatileStatus.PerishSong -> "perish_song(${status.turns})"
        is VolatileStatus.Encored -> "encored(" + (status.move?.let { "move:${it.id.value} ${it.name}, " } ?: "") + "${status.turns} turns)"
        is VolatileStatus.Disabled -> "disabled(" + (status.move?.let { "move:${it.id.value} ${it.name}, " } ?: "") + "${status.turns} turns)"
        else -> status.toString().substringBefore('(').replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }
}
