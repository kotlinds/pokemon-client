package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.ActionSettings
import dev.kotlinds.pokemonclient.actions.FlyAdvisor
import dev.kotlinds.pokemonclient.actions.ReachSurvey
import dev.kotlinds.pokemonclient.data.BattleKnowledge
import dev.kotlinds.pokemonclient.data.CatchChance
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Matchups
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the application running an agent lets it know and do, in one object carried from the app's settings to every
 * reader: the actions offered ([ActionSettings], built from it by [actionSettings]) and what the views show
 * ([AgentView], [StateView], [MapView]) follow the same options, so they can never disagree (a walkthrough allowing
 * hidden items in actions but not in the view...).
 */
data class AgentOptions(
    /** Which actions the agent gets: raw buttons only, or the assisted ones too. */
    val mode: ActionMode = ActionMode.ASSISTED,
    /** What the agent may know beyond the screen (estimated effectiveness from [KnowledgeLevel.POKEDEX] on). */
    val knowledge: KnowledgeLevel = KnowledgeLevel.POKEDEX,
    /** Walks solve movement puzzles by themselves ([ActionSettings.solvePuzzles]); when false the agent operates them. */
    val solvePuzzles: Boolean = true,
    /**
     * Where the ways out lead is hidden ([ActionSettings.hideDestinations]): the views list exits without destination,
     * go_to stays on the current map, and no hint names another map (fly suggestions, blockers' reasons).
     */
    val hideDestinations: Boolean = false,
) {
    /** The agent is allowed a walkthrough: what the player hasn't seen (hidden items, unseen teleports, story goals). */
    val walkthrough: Boolean get() = knowledge.allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)

    /** What the recipes may do by themselves under these options. */
    val actionSettings: ActionSettings get() = ActionSettings(solvePuzzles = solvePuzzles, revealHidden = walkthrough, hideDestinations = hideDestinations)
}

/**
 * The agent-facing view of the game: everything an agent reads at one moment, assembled the same way for every host
 * (the app's sessions for MCP agents and its own loop, the bench), so what an agent gets can't depend on who runs it.
 *
 * It carries the messages and events given to it ([describe]'s `events`: which ones is the host's business, see
 * `EventFeed`), the state ([StateView]: screen, position, team, battle), the bag and boxes, the battle estimates
 * (effectiveness, switch candidates, what is known of the opponents, catch chance), the text map ([MapView]), the
 * movement puzzles left to the agent, the story goals and blockers (walkthrough), and the actions possible now.
 *
 * Stateful, one per agent: what the agent has seen (teleports once on screen: [Sightings]), what it learned of the
 * opponents this battle ([BattleKnowledge]), the fly suggestions (each costs a search of the world) and what a compact
 * answer already sent (the team, the position).
 */
class AgentView(private val game: PokemonGame, private val registry: ActionRegistry = ActionRegistry.of()) {

    /** How much a description says. */
    enum class Detail {
        /** An `act` answer: what the agent already has is left out (unchanged team, map when not moved, money / badges, the bag, the actions' valid values). */
        COMPACT,

        /** The state as `get_state` gives it by default. */
        STANDARD,

        /** Everything: also the PC boxes, the game options and each action's description. */
        FULL,
    }

    private val sightings = Sightings()
    private val battleKnowledge = BattleKnowledge()
    private val flyAdvisor = FlyAdvisor(game)
    private val compactView = CompactView()

    /**
     * Writes the view of [state] into [into]: [events] are the game events the agent hasn't received yet (oldest
     * first), [options] what it may know and do, [detail] how much to say.
     */
    fun describe(into: JsonObjectBuilder, state: GameState, events: List<GameEvent>, options: AgentOptions, detail: Detail) = with(into) {
        val compact = detail == Detail.COMPACT
        val full = detail == Detail.FULL
        val messages = events.filterIsInstance<GameEvent.TextShown>()
        if (messages.isNotEmpty()) {
            putJsonArray(MESSAGES) {
                messages.forEach { add(JsonPrimitive("[${it.source.name.lowercase()}] " + (it.speaker?.let { s -> "$s: " } ?: "") + it.text.replace('\n', ' '))) }
            }
        }
        val other = events.mapNotNull(::describeEvent).distinct()
        if (other.isNotEmpty()) put(EVENTS, JsonArray(other.map(::JsonPrimitive)))
        val walkthrough = options.walkthrough
        val hidden = options.hideDestinations
        sightings.observe(state.field)
        val view = StateView.state(state, showHidden = walkthrough, sightings = sightings, hideDestinations = hidden)
        val (viewEntries, moved) = compactView.take(view, compact)
        viewEntries.forEach { (k, v) -> put(k, v) }
        // get_state lists the bag (one line per pocket); act answers leave it out.
        if (!compact) state.bag?.takeIf { it.isNotEmpty() }?.let { put("bag", StateView.bag(it)) }
        if (full) {
            state.storage?.let { put("boxes", StateView.storage(it)) }
            state.options?.let { put("options", StateView.options(it)) }
        }
        battle(state, messages, options, compact)
        // The text map of the surroundings: the common MapView on the ROM's maps (none when the game has no ROM maps).
        if (state.screen is Screen.Overworld) {
            val field = state.field
            val area = field?.let { game.world?.areaOf(it.mapId) }
            if (compact && !moved) put("map", MAP_UNCHANGED)
            else if (field != null && area != null) {
                // What reaching each listed target needs: one search over this map for the whole view.
                val survey = ReachSurvey(game, state, options.actionSettings)
                MapView.render(area, field, game::mapName, world = game.world, showHidden = walkthrough, hideDestinations = hidden, reach = survey::of)
                    .forEach { (k, v) -> put(k, v) }
            }
        }
        // Movement puzzles left to the agent: say so where it matters (a puzzle here, or the full state).
        val here = state.field
        if (!options.solvePuzzles && here != null && (full || here.puzzle != null)) put("movement_puzzles", PUZZLES_LEFT_TO_AGENT)
        story(state, walkthrough, hidden)
        actions(state, options.mode, detail)
    }

    /** [describe] as an object of its own. */
    fun describe(state: GameState, events: List<GameEvent>, options: AgentOptions, detail: Detail): JsonObject =
        buildJsonObject { describe(this, state, events, options, detail) }

    /**
     * The battle estimates, within the knowledge level: effectiveness of the moves, whom to switch to, what is known
     * of the opponents (revealed abilities and held items, learned from [messages]) and the catch chance.
     */
    private fun JsonObjectBuilder.battle(state: GameState, messages: List<GameEvent.TextShown>, options: AgentOptions, compact: Boolean) {
        val data = game.data
        val battle = state.battle
        battleKnowledge.observe(state, messages.filter { it.source == TextSource.BATTLE }.map { it.text }, data)
        if (battle == null || data == null || !options.knowledge.allows(KnowledgeLevel.POKEDEX)) return
        val matchups = Matchups.estimate(battle, data, battleKnowledge)
        if (matchups.isNotEmpty()) {
            put("effectiveness", JsonArray(matchups.map { JsonPrimitive("${it.move} → ${it.target.wire}: ${it.label}") }))
        }
        // Whom to switch to: in the full state, and in compact answers where a switch is chosen (party screen,
        // "change Pokémon?" after a K.O.).
        if (!compact || choosingSwitch(state.screen)) {
            val party = Matchups.party(battle, state.party, data, battleKnowledge)
            if (party.isNotEmpty()) put("party_effectiveness", JsonArray(party.map { JsonPrimitive(it.line(battle.isDouble)) }))
        }
        battleKnowledge.describe(battle, data).takeIf { it.isNotEmpty() }?.let { put("opponents_known", JsonArray(it.map(::JsonPrimitive))) }
        val balls = state.bag.orEmpty().firstOrNull { it.name == "balls" }?.items.orEmpty()
        CatchChance.estimate(battle, balls, data)?.let { estimate ->
            put("catch", buildJsonObject {
                put("catch_rate", estimate.catchRate)
                put("chance_per_ball", JsonArray(estimate.balls.map { JsonPrimitive(it.label) }))
            })
        }
    }

    /** The story goals and what blocks a way, for agents allowed a walkthrough. */
    private fun JsonObjectBuilder.story(state: GameState, walkthrough: Boolean, hidden: Boolean) {
        val story = state.story ?: return
        if (!walkthrough) return
        // Always a list: one goal, or every open goal when the game leaves the choice (the Kanto gyms...). A goal in
        // one place says which visited fly destination lands nearest when flying beats walking there. Destinations
        // hidden: no fly suggestion (it says which town lands nearest the goal: where it is).
        put("story_goals", JsonArray(story.openGoals.map { goal ->
            val fly = goal.place?.takeIf { !hidden }?.let { flyAdvisor.suggest(state, it) }
            JsonPrimitive(goal.description + (fly?.let { " (${it.text})" } ?: ""))
        }))
        // The walkthrough's reasons often say where to go to lift a blocker ("clear the Slowpoke Well, north of
        // town"): only that it blocks while destinations are hidden.
        if (story.blockers.isNotEmpty()) put("blocked_by", JsonArray(story.blockers.map {
            JsonPrimitive("${it.target}: " + if (hidden) BLOCKER_WHERE_HIDDEN else it.reason)
        }))
    }

    /** The actions possible now (names only in a compact answer), and those shown but not possible, with why. */
    private fun JsonObjectBuilder.actions(state: GameState, mode: ActionMode, detail: Detail) {
        if (detail == Detail.COMPACT) {
            put("actions", JsonArray(registry.available(state, mode).map { JsonPrimitive(it.name) }))
            return
        }
        put("actions", buildJsonArray {
            registry.available(state, mode).forEach { a ->
                add(buildJsonObject {
                    put("type", a.name)
                    if (detail == Detail.FULL) put("description", a.description)
                    a.choices.forEach { (param, choices) ->
                        put(param, JsonArray(choices.map { JsonPrimitive(if (it.label == it.value) it.value else "${it.value} = ${it.label}") }))
                    }
                })
            }
        })
        val unavailable = registry.unavailable(state, mode)
        if (unavailable.isNotEmpty()) {
            put("unavailable", JsonArray(unavailable.map { JsonPrimitive("${it.name}: ${it.detail}" + (it.hint?.let { h -> " ($h)" } ?: "")) }))
        }
    }

    /** Screens of a battle where the player picks the Pokémon to send in. */
    private fun choosingSwitch(screen: Screen) =
        screen is Screen.PartyGrid || (screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP

    /** One line for the agent per event worth telling (texts are given as messages; screen changes aren't told). */
    private fun describeEvent(event: GameEvent): String? = when (event) {
        is GameEvent.PokemonObtained -> "obtained ${event.species} (${event.mon})"
        is GameEvent.Evolved -> "${event.from} evolved into ${event.to}"
        is GameEvent.LevelUp -> "${event.name?.let { "$it (${event.mon})" } ?: event.mon} reached level ${event.level}"
        is GameEvent.ItemReceived -> "received ${event.item} x${event.quantity}"
        is GameEvent.ShopBonus -> "the clerk added ${event.item} x${event.quantity} as a bonus"
        is GameEvent.BadgeReceived -> "received the ${event.badge} badge"
        is GameEvent.PokegearUpgraded -> "Pokégear upgraded: ${event.card.name.lowercase()} card" +
            if (event.card == PokegearCard.EXPANSION) " (Kanto's radio stations, among them the Poké Flute)" else ""
        is GameEvent.HumanInput -> "the human pressed buttons"
        is GameEvent.Caught -> "caught ${event.name}" + (if (event.name != event.species) " (${event.species})" else "") +
            (event.level?.let { " Lv$it" } ?: "") + " (${event.mon})" + (event.boxName?.let { ", sent to $it (party full)" } ?: ", in the party")
        is GameEvent.SentToBox -> "${event.name} (${event.mon}) was sent to the PC: ${event.boxName}"
        is GameEvent.LearnedMove -> "${event.name} (${event.mon}) learned ${event.move}" + (event.forgot?.let { ", forgot $it" } ?: "")
        else -> null
    }

    companion object {
        /** The texts the game showed since the agent's last call (`[source] speaker: text`). */
        const val MESSAGES = "messages_since_last_call"

        /** The other events since the agent's last call (captures, level ups, items received...), one line each. */
        const val EVENTS = "events_since_last_call"

        /** A compact answer's map while the player hasn't moved since the previous answer. */
        const val MAP_UNCHANGED = "unchanged (you haven't moved)"

        const val PUZZLES_LEFT_TO_AGENT = "left to you: go_to and interact only walk (they never push a boulder or an ice block, " +
            "never step on a platform trigger or a lift unless it is the destination you gave); a way that needs one fails with " +
            "PUZZLE_LEFT_TO_AGENT naming it. Operate them yourself: step into a boulder after using Strength on it (interact), " +
            "slide into an ice block, go_to / step onto a trigger or a lift; push moves a boulder into its hole"

        /** A story blocker while destinations are hidden: its walkthrough reason names places, so only what it does. */
        const val BLOCKER_WHERE_HIDDEN = "blocks a way until the story moves on (destinations are hidden: the reason, which " +
            "names places, is left out); talk to them to learn what they wait for"
    }
}

/**
 * What a compact answer keeps of the state view ([StateView.state]): the team only when it changed since the previous
 * answer, never the money and badges (`get_state` has them); everything else as is. Remembers the team and position
 * it last saw, compact or not (a full state sent the team too).
 */
internal class CompactView {
    private var lastTeam: JsonElement? = null
    private var lastPosition: JsonElement? = null

    /** The entries of [view] to send ([compact] or full), and whether the player moved since the previous view. */
    fun take(view: JsonObject, compact: Boolean): Taken {
        val moved = view[POSITION] != lastPosition
        val teamChanged = view[TEAM] != lastTeam
        val entries = view.entries.mapNotNull { (k, v) ->
            when {
                !compact -> k to v
                k == TEAM -> k to (if (teamChanged) v else JsonPrimitive(TEAM_UNCHANGED))
                k in LEFT_OUT -> null
                else -> k to v
            }
        }
        lastTeam = view[TEAM]
        lastPosition = view[POSITION]
        return Taken(entries, moved)
    }

    /** The view entries to send, and whether the player moved (a compact answer then skips the map). */
    data class Taken(val entries: List<Pair<String, JsonElement>>, val moved: Boolean)

    companion object {
        const val TEAM = "team"
        const val POSITION = "position"
        const val TEAM_UNCHANGED = "unchanged since your last call"

        /** Never in a compact answer. */
        val LEFT_OUT = setOf("money", "badges")
    }
}
