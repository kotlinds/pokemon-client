package dev.kotlinds.pokemonclient.state

import dev.kotlinds.pokemonclient.Direction

/**
 * Everything the client knows about the game at one frame, decoded from RAM into common typed models.
 *
 * Games fill it from their own memory layout; agents, actions and views only use this.
 */
data class GameState(
    /** Frame number of the console when this state was read. */
    val frame: Long,
    /** What the game waits for right now. */
    val screen: Screen,
    val player: PlayerInfo?,
    /** The party in its saved order. In battle, values (HP, status) come from the battle's copy. */
    val party: List<PartyMon>,
    val bag: List<BagPocket>?,
    val battle: BattleState?,
    val field: FieldState?,
    /** Readings that were rejected or uncertain (a Pokémon whose checksum failed...). */
    val warnings: List<ReadWarning> = emptyList(),
    /**
     * Key items registered for quick use, in button order: the first one is used with Y in the field (no bag),
     * null for a free slot.
     */
    val registeredItems: List<ItemId?> = emptyList(),
    /**
     * Where the story stands (from the game's flags and variables): the next goal and what blocks the way. The goals
     * and the blockers' [Blocker.reason] are walkthrough knowledge (shown to agents only at
     * [dev.kotlinds.pokemonclient.data.KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH]); what blocks a way ([Blocker.target],
     * [Blocker.scene]) is what the player sees on screen, shown at every level.
     */
    val story: StoryState? = null,
    /** The PC boxes (from the save data, readable anywhere), null when unreadable. */
    val storage: PcStorage? = null,
    /** The game's OPTIONS (text speed, battle scene, battle style), null when unreadable. */
    val options: GameOptions? = null,
    /** What the start menu offers yet (the story unlocks it bit by bit), null when unknown. */
    val startMenu: Set<StartMenuFeature>? = null,
    /**
     * Whether this game has a Pokégear (the phone, map and radio of HeartGold / SoulSilver): false for a game without
     * one (Platinum has a Pokétch instead), whose Pokégear actions (`tune_radio`) then don't exist (never listed, refused
     * as NOT_SUPPORTED_BY_GAME: [dev.kotlinds.pokemonclient.actions.Availability.NotInThisGame]); null when unknown (a
     * state built without its game: taken as present). Which cards it has is [PlayerInfo.pokegearCards].
     */
    val pokegear: Boolean? = null,
    /**
     * The field moves of the game ([dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]) as the party and the badges
     * allow them now ([dev.kotlinds.pokemonclient.world.FieldMoves.access]): what the action list needs (Fly) without
     * the game at hand, and what the walks use. Every game fills it in its `state()` (every kind present, those it
     * doesn't have as `Unknown`). Null: not read, a state built without its game (tests): the actions then read it
     * with the game's rules ([dev.kotlinds.pokemonclient.world.FieldMoves.of]) rather than taking "no field move",
     * which would silently hide Fly and drop Surf from the routes.
     */
    val fieldMoves: Map<dev.kotlinds.pokemonclient.world.FieldMoveKind, dev.kotlinds.pokemonclient.world.FieldMoveAccess>? = null,
    /** The species seen and caught (from the save data, readable anywhere), null when unreadable. */
    val pokedex: PokedexState? = null,
    /**
     * The save's event flags (story progress), when the game reads them: which people of the other maps are there
     * (an object is hidden once its event flag is set, [dev.kotlinds.pokemonclient.world.PersonTemplate.hiddenByFlag]),
     * e.g. to tell an exit whose arrival tile someone blocks. Null when unknown.
     */
    val eventFlags: EventFlags? = null,
)

/**
 * A snapshot of the save's event flags: [bits] holds flag `n` in bit `n % 8` of byte `n / 8` (the Gen 4 layout of
 * `SaveVarsFlags.flags`). Flags past its end are unknown.
 */
class EventFlags(bits: ByteArray) {
    /** A copy: the flags of a state never change once read. */
    private val bits: ByteArray = bits.copyOf()

    /** True / false when flag [id] is set / clear, null when unknown. */
    operator fun get(id: Int): Boolean? {
        if (id < 0 || id / 8 >= bits.size) return null
        return (bits[id / 8].toInt() shr (id % 8)) and 1 == 1
    }

    override fun equals(other: Any?): Boolean = other is EventFlags && bits.contentEquals(other.bits)
    override fun hashCode(): Int = bits.contentHashCode()
}

/** Start menu entries the story unlocks. */
enum class StartMenuFeature {
    /** The menu itself opens (X) once the bag is given; until then nothing opens. */
    BAG,
    TRAINER_CARD,
    SAVE,
    OPTIONS,
}

/** The story's progress, read from the game's flags and variables (never from what is displayed). */
data class StoryState(
    /** The next step of the main story (the first of [openGoals]), or null when the table doesn't know (or the story is over). */
    val goal: StoryStep?,
    /** People and triggers of the current map that block a way, with why. */
    val blockers: List<Blocker> = emptyList(),
    /**
     * Every step of the main story that can be done now, in the story's order: several where the game leaves the
     * choice (the Kanto gyms...), each saying where. [goal] is the first.
     */
    val openGoals: List<StoryStep> = listOfNotNull(goal),
    /**
     * Every step of the main story already done, in the story's order (the same steps as [openGoals]): how far the
     * player got, e.g. to measure a run's milestones. Empty when the game has no story table.
     */
    val completed: List<StoryStep> = emptyList(),
)

/** One step of a game's story table. */
data class StoryStep(
    /** Stable id of the step, e.g. `johto:badge_rising` (language-independent). */
    val id: String,
    /** What to do, written for the agent (our walkthrough text, not the game's). */
    val description: String,
    /** The map (zone id) where it happens, when it is one place: what a fly suggestion is measured to. */
    val place: Int? = null,
)

/**
 * Something on the current map that blocks a way, and the condition that lifts it when it's known.
 *
 * What it is ([target], [scene]) is what the player sees on screen (a person standing in the way, a scene that turns
 * them back): agents get it at every knowledge level. Why it blocks and what lifts it ([reason]) is our walkthrough.
 */
data class Blocker(
    /** The person or trigger, as targets use it: `person:N`, `trigger:N`. */
    val target: String,
    /** Why it blocks and how to get past (our walkthrough text: story knowledge, never shown below a walkthrough). */
    val reason: String,
    /** The mechanism, when it is a known one (a password door, a Pokémon to battle). */
    val cause: BlockerCause? = null,
    /** For a coordinate trigger (`trigger:N`): where it is and what stepping there does; null for a person. */
    val scene: SceneTrigger? = null,
)

/**
 * A coordinate trigger of the map that starts a story scene now: the same for every game (Gen 4: the zone's
 * coordinate events whose variable has the awaited value).
 */
data class SceneTrigger(
    /** Its index in the zone's coordinate events: the `N` of `trigger:N`. */
    val id: Int,
    /** The tiles it covers (columns). */
    val x: IntRange,
    /** The tiles it covers (rows). */
    val y: IntRange,
    /**
     * True when the scene turns the player back and starts again each time they step there, as long as the story
     * hasn't moved on (a guard sending them back); false for an event that happens once and then lets them through (a
     * rival's battle); null when not known.
     */
    val repeats: Boolean?,
) {
    /** "540,177" or "540,177..179": the tiles, as coordinates elsewhere in the view are written. */
    val tiles: String get() = "${span(x)},${span(y)}"

    private fun span(r: IntRange) = if (r.first == r.last) "${r.first}" else "${r.first}..${r.last}"
}

/** The player's trainer card. */
data class PlayerInfo(
    val name: String,
    val money: Long,
    val badges: List<String>,
    val trainerId: Long,
    /** Time played, as the game counts it (the "Continue" screen and the trainer card show it). */
    val playTime: PlayTime? = null,
    /**
     * The badges owned, by the game's badge id (language-independent: rules check these, never [badges]' names).
     * HGSS: 0..7 Johto (Zephyr .. Rising), 8..15 Kanto (Boulder .. Earth). Empty when unknown.
     */
    val badgeIds: Set<Int> = emptySet(),
    /** The cards the Pokégear has gained (Map, Radio, Expansion), null when unknown. */
    val pokegearCards: Set<PokegearCard>? = null,
    /** Items Mom bought that wait at a Poké Mart's delivery man. */
    val momParcels: List<MomParcel> = emptyList(),
    /**
     * The fly destinations visited (from the save's flags, readable anywhere), in the game's order: where `fly` can
     * go once the party can fly. Empty when unknown.
     */
    val flyDestinations: List<FlyDestination> = emptyList(),
)

/**
 * A place Fly takes the player to: [id] as the `fly` action and the fly map take it (`fly:<map id>`), [zone] the map
 * it lands on (Victory Road's lands on Route 26), [name] for display only (the fly map's label).
 *
 * Where Fly only reaches the player's region (HGSS: Johto or Kanto), [fromAnyRegion] destinations can be chosen from
 * every region, and from a [regionHub] every visited destination can (HGSS: Indigo Plateau; `fly` goes through it).
 */
data class FlyDestination(
    val id: String,
    val zone: Int,
    val name: String,
    val fromAnyRegion: Boolean = false,
    val regionHub: Boolean = false,
)

/** A play time counter. */
data class PlayTime(val hours: Int, val minutes: Int, val seconds: Int) {
    /** "24:59" as the game shows it (hours:minutes). */
    override fun toString() = "$hours:${minutes.toString().padStart(2, '0')}"
}

/** One bag pocket. */
data class BagPocket(val name: String, val items: List<BagItem>)

/** One item stack. */
data class BagItem(val item: Named<ItemId>, val quantity: Int)

/** Where the player is in the world. */
data class FieldState(
    val mapId: Int,
    /** The map's name ([dev.kotlinds.pokemonclient.PokemonGame.mapName] of [mapId]): what views and messages show. */
    val mapName: MapName,
    val x: Int,
    val y: Int,
    /** Height of the player (map units), to tell bridges and upper floors apart. */
    val height: Int,
    val facing: Direction?,
    val movement: MovementMode,
    /** True while the player is between two tiles (walking, sliding...). */
    val moving: Boolean,
    /** People and objects on the map right now (live positions, not the map's initial ones). */
    val objects: List<FieldObject> = emptyList(),
    /** The puzzle of this map (switches, shutters, teleports) and its live state, when the game knows one here. */
    val puzzle: PuzzleState? = null,
    /**
     * Map things already taken, by target id (`hidden_item:N` whose flag is set): still in the ROM's events, but
     * gone from the game.
     */
    val pickedUp: Set<String> = emptySet(),
    /** A trainer saw the player: it walks up to them and a battle follows (until the battle starts). */
    val trainerEncounter: Boolean = false,
    /**
     * The game's id of the trainer who saw the player ([FieldTrainer.trainerId] of one of [objects]) while
     * [trainerEncounter], when the game tells it; null otherwise.
     */
    val engagedTrainerId: Int? = null,
    /**
     * Whether the map lets the player fly away: its map header's flag, the game's only rule for the place (outdoors in
     * the normal games; the map randomizer allows it everywhere), null when unknown.
     */
    val flyAllowed: Boolean? = null,
    /** Whether the map has a PC (Pokémon storage), null when unknown. */
    val hasPc: Boolean? = null,
    /**
     * Tiles of the map's coordinate triggers active right now (their variable has the awaited value): stepping on one
     * runs a script (a scene, a hole, a twig...). Filled when the game knows the map's events.
     */
    val activeTriggers: Set<Pair<Int, Int>> = emptySet(),
    /** Whether the bicycle can be ridden on this map, null when unknown. */
    val bikeAllowed: Boolean? = null,
    /**
     * Invisible things of the map that answer A ([FieldExaminable]), present now (gone once their event is done).
     * Their tiles block like walls. Walkthrough knowledge unless they have a cue: the views decide what to show.
     */
    val examinables: List<FieldExaminable> = emptyList(),
    /** The radio programme whose music plays over the field (the Pokégear radio left on), null when none. */
    val radioMusic: RadioStation? = null,
    /**
     * Steps left of the Repel at work (0 when none), null when unknown. While it works, wild Pokémon of a lower level
     * than the first Pokémon of the party able to fight don't appear.
     */
    val repelSteps: Int? = null,
    /**
     * The player runs without holding B (the running shoes switched on: HeartGold / SoulSilver's touch screen shoe
     * button), null when unknown. [movement] still says WALK: it tells the field state, not the pace.
     */
    val autoRun: Boolean? = null,
    /**
     * The player owns the running shoes (holding B runs), null when unknown (taken as owned). Without them walks walk
     * whatever `run` says, and the routes count walking steps.
     */
    val runningShoes: Boolean? = null,
)

/** A person or object standing on the map. */
data class FieldObject(
    /** Stable, language-independent id: `person:<event id>` (the map's local id of the object). */
    val id: String,
    /** What a player would call it (display only). */
    val label: String,
    val kind: FieldObjectKind,
    val x: Int,
    val y: Int,
    val facing: Direction?,
    /** The service this person offers, when it's one recipes rely on (heal, shop). */
    val role: PersonRole? = null,
    /** For an [FieldObjectKind.OBSTACLE]: which one (it tells the field move that clears it). */
    val obstacle: ObstacleKind? = null,
    /** For a trainer: who it is, whether it's beaten, how far it sees. */
    val trainer: FieldTrainer? = null,
    /**
     * Its height, in the units of [FieldState.height], when the game tells it. The game only talks to (or picks up)
     * an object faced at the player's own height (sub_0203DBD4: both position vectors' Y must be equal): from the
     * water, a person standing on the shore above it answers nothing.
     */
    val height: Int? = null,
    /** For a shop clerk: what the shop sells, when known before talking. */
    val catalog: List<ShopItem>? = null,
    /**
     * For a door placed as an object ([PersonRole.GATE]): true once it has slid aside (open: it no longer stands in the
     * way), false while it is closed, null when the game doesn't tell (taken as closed).
     */
    val open: Boolean? = null,
)

/** Obstacles placed on the map as objects, told by their sprite. */
enum class ObstacleKind {
    /** A small tree: Cut removes it. */
    CUT_TREE,

    /** A cracked rock: Rock Smash breaks it. */
    SMASH_ROCK,

    /** A boulder: Strength pushes it one tile. */
    BOULDER,

    /**
     * An ice block (the Mahogany Gym): sliding on the ice into it pushes it, until it stops against something; two
     * blocks meeting freeze together (both then face north and can't be pushed).
     */
    ICE_BLOCK,
}

/** People actions look for, told by their sprite (never by what they say). */
enum class PersonRole {
    /** Heals the party (Pokémon Center). */
    NURSE,

    /** Sells items (Poké Mart counter). */
    CLERK,

    /** A door or gate placed as an object (League doors, the Radio Tower and Rocket HQ doors): it opens after an event. */
    GATE,

    /** A shutter of a map puzzle (Goldenrod Tunnel): switches open and close it ([FieldState.puzzle]). */
    SHUTTER,

    /** Stands in a passage until a story event or a talk moves them ([GameState.story] blockers say what lifts it). */
    BLOCKER,
}

/** Kinds of [FieldObject]. */
enum class FieldObjectKind {
    PERSON,

    /** The Pokémon walking behind the player: it moves out of the way, never an obstacle. */
    FOLLOWER,

    /** A Poké Ball lying on the ground: an item to pick up. */
    ITEM_BALL,

    /** A Cut tree, a Rock Smash rock, a Strength boulder... */
    OBSTACLE,
}

/** How the player moves. */
enum class MovementMode { WALK, RUN, BIKE, SURF }

/** A reading the decoder didn't trust. */
data class ReadWarning(val kind: Kind, val detail: String) {
    enum class Kind { POKEMON_CHECKSUM, INCONSISTENT_VALUE, UNREADABLE_POINTER, OTHER }
}
