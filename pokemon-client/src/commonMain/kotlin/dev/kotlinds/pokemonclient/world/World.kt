package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * The static world of a game, decoded from its ROM: areas of tiles with collision, behaviours and heights, and their
 * events (warps, signs, people, triggers). Games implement it with their own file formats (Gen 4: map headers,
 * matrices, land_data with BDHC heights, zone events); the graph and the pathfinding on top are shared.
 */
interface WorldSource {
    /**
     * The area containing zone (map) [zoneId]: all the zones sharing its coordinate space (on the Gen 4 overworld,
     * every outdoor zone of the region shares one matrix; a building is its own area). Null when unknown. Cached.
     */
    fun areaOf(zoneId: Int): Area?

    /** Number of zones (maps): ids are `0 until zoneCount` (0 when unknown). Used to find a map by name. */
    val zoneCount: Int get() = 0

    /**
     * Whether Fly can be used from zone [zoneId]: the flag of its map header, as the game checks it (outdoors in the
     * normal games; a randomizer may allow it anywhere), null when unknown.
     */
    fun flyAllowed(zoneId: Int): Boolean? = null

    /** Whether the bicycle can be ridden in zone [zoneId], null when unknown. */
    fun bikeAllowed(zoneId: Int): Boolean? = null

    /** The region zone [zoneId] belongs to (Fly only reaches the region the player is in), null when unknown. */
    fun regionOf(zoneId: Int): Region? = null

    /** The lift whose room is zone [zoneId] ([Elevator]), null when it isn't one or when the game doesn't tell. */
    fun elevatorOf(zoneId: Int): Elevator? = null

    /**
     * Whether this game's wild encounter tables are decoded ([EncounterTables.DECODED]: [encounterChance] and
     * [wildEncounters] are the game's, 0 and null mean "none") or not yet ([EncounterTables.UNKNOWN]: they say nothing,
     * wild Pokémon may appear on every tile whose kind has encounters).
     */
    val encounterTables: EncounterTables get() = EncounterTables.UNKNOWN

    /**
     * The chance (0..1) that one encounter check of zone [zoneId] starts a wild battle, as the game rolls it under
     * [conditions]: on its land encounter tiles ([TileInfo.landEncounters]), or surfing on its water ([water],
     * [TileKind.Water.wildEncounters]). The game counts a check on every step onto such a tile and on every turn in
     * place there. 0 when the zone has no such encounters, or when the tables are [EncounterTables.UNKNOWN] (no weight
     * in routes then, see [StepWeights]).
     */
    fun encounterChance(zoneId: Int, water: Boolean, conditions: EncounterConditions): Double = 0.0

    /**
     * The wild Pokémon of zone [zoneId] ([WildEncounters]), null when it has none, or when the tables are
     * [EncounterTables.UNKNOWN].
     */
    fun wildEncounters(zoneId: Int): WildEncounters? = null
}

/** Whether a [WorldSource] knows its game's wild encounter tables ([WorldSource.encounterTables]). */
enum class EncounterTables {
    /** Decoded from the ROM: a zone without a table, or a chance of 0, really has no wild Pokémon there. */
    DECODED,

    /**
     * Not decoded for this game yet: nothing is known per zone. Wild Pokémon may appear on every tile whose kind has
     * encounters (tall grass, cave floors, surfable water), routes give them no weight, and `lookup encounters` says
     * the tables are unknown (never "no wild Pokémon").
     */
    UNKNOWN,
}

/** A region of the world (HGSS: Johto, Kanto): [id] is the game's number, [name] is for display only. */
data class Region(val id: Int, val name: String)

/**
 * A rectangle of tiles in the coordinates the game reports for the player, with their properties and the events of
 * every zone inside it.
 */
class Area(
    /** Identifier of the coordinate space (the Gen 4 matrix id). */
    val id: Int,
    val name: String,
    /** World coordinates of the top-left tile of [tiles]. */
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    /** Row-major tiles (`tiles[(y - originY) * width + (x - originX)]`), null where nothing is loaded. */
    private val tiles: Array<TileInfo?>,
    val warps: List<Warp> = emptyList(),
    val signs: List<Sign> = emptyList(),
    val people: List<PersonTemplate> = emptyList(),
    val triggers: List<Trigger> = emptyList(),
    /** Zone (map) of each tile, row-major like the tiles, null where unknown. */
    private val zones: IntArray? = null,
    /** Triggers whose script warps the player elsewhere on the same zone (warp pads, trap tiles). */
    val scriptWarps: List<ScriptWarp> = emptyList(),
    /** Triggers whose script moves the player to another zone (holes to the floor below). */
    val triggerWarps: List<TriggerWarp> = emptyList(),
    /** Whether several maps share this coordinate space ([AreaKind.OVERWORLD]) or it is one map alone. */
    val kind: AreaKind = AreaKind.SINGLE_MAP,
) {
    /** The zone (map) id at (x, y), when known. */
    fun zoneAt(x: Int, y: Int): Int? {
        if (zones == null || x < originX || y < originY || x >= originX + width || y >= originY + height) return null
        return zones[(y - originY) * width + (x - originX)].takeIf { it >= 0 }
    }

    /**
     * The bounding box of each zone's tiles (`[minX, minY, maxX, maxY]`, world coordinates), computed once: where to
     * look for a zone's tiles in a shared area.
     */
    val zoneBounds: Map<Int, IntArray> by lazy {
        val bounds = HashMap<Int, IntArray>()
        if (zones != null) for (i in zones.indices) {
            val z = zones[i].takeIf { it >= 0 } ?: continue
            val x = originX + i % width
            val y = originY + i / width
            val b = bounds.getOrPut(z) { intArrayOf(x, y, x, y) }
            if (x < b[0]) b[0] = x
            if (y < b[1]) b[1] = y
            if (x > b[2]) b[2] = x
            if (y > b[3]) b[3] = y
        }
        bounds
    }

    /**
     * The tiles of zone [zone]'s scene triggers active now: the one rule of every reader of the triggers (the game's
     * state, the walks, the reachability survey). A trigger counts when stepping on it shows something: not a
     * placeholder ([Trigger.inert]: walked like floor) and [Trigger.startsScene] with the save's variables
     * ([variableOf]) and flags ([flagOf]).
     */
    fun sceneTriggerTiles(zone: Int, variableOf: (Int) -> Int?, flagOf: (Int) -> Boolean?): Set<Pair<Int, Int>> =
        triggers.filter { it.zone == zone && !it.inert && it.startsScene(variableOf, flagOf) }.flatMapTo(mutableSetOf()) { it.tiles }

    fun tile(x: Int, y: Int): TileInfo? {
        if (x < originX || y < originY || x >= originX + width || y >= originY + height) return null
        return tiles[(y - originY) * width + (x - originX)]
    }

    /**
     * True when (x, y) is drawn as walkable floor but lies in the void around a map loaded alone
     * ([AreaKind.SINGLE_MAP]): a building's block is 32×32 tiles, its room a corner of it, the rest has no collision
     * and no surface, and the game never leads there, except one tile past an exit door when a map-randomized warp
     * arrives on a door taken the other way (the exit step), and the player then walks off into it and may not get back
     * (NOTES-run-map-randomizer: Goldenrod Underground).
     *
     * The rule: the void is what can't be reached, whatever moves it takes (water, ledges, walls to climb, the lava
     * crossed by platforms), from any place where the game puts the player or something to reach: the warps (where
     * the player arrives), holes and warp pads, the coordinate triggers (the scripts that move the player: carts,
     * lifts, platforms), people, signs and hidden items. Never on an [AreaKind.OVERWORLD]: its maps are walked into
     * each other, and a part of it walled off from every event (a field behind a fence, a beach) is still a place the
     * map draws for the player, not a void. Moving floors of a puzzle (lift tops, cart stations) are the view's to
     * keep out of it too ([dev.kotlinds.pokemonclient.view.MapView]).
     */
    fun outside(x: Int, y: Int): Boolean {
        if (kind == AreaKind.OVERWORLD) return false
        val tile = tile(x, y) ?: return false
        return !tile.blocked && passable(tile) && !inside[(y - originY) * width + (x - originX)]
    }

    /**
     * The void ([outside]) once [places] are places of the area too: the live puzzle's moving floors and landings (a
     * lift's top, a cart station, a platform), which the static events don't tell. The void tiles connected to them
     * aren't void. One flood over the void tiles only, per call: built once per view.
     */
    fun voidAround(places: Collection<Pair<Int, Int>>): (Int, Int) -> Boolean {
        if (kind == AreaKind.OVERWORLD) return { _, _ -> false }
        val reached = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Int>>()
        fun visit(x: Int, y: Int) {
            if (!outside(x, y)) return
            if (reached.add((y - originY) * width + (x - originX))) stack.addLast(x to y)
        }
        places.forEach { (x, y) -> SEED_AROUND.forEach { (dx, dy) -> visit(x + dx, y + dy) } }
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            SEED_AROUND.forEach { (dx, dy) -> visit(x + dx, y + dy) }
        }
        return { x, y -> outside(x, y) && (y - originY) * width + (x - originX) !in reached }
    }

    /** The tiles connected to the area's events ([outside]), row-major like the tiles. */
    private val inside: BooleanArray by lazy {
        val reached = BooleanArray(width * height)
        val stack = ArrayDeque<Int>()
        fun seed(x: Int, y: Int) {
            // An event on a wall (a sign, a bookshelf) opens onto the tiles around it; one on a passable tile is
            // only there (the tile past an exit mat may be the void).
            val own = tile(x, y) ?: return
            for ((dx, dy) in if (passable(own)) SEED_AROUND.take(1) else SEED_AROUND) {
                val sx = x + dx
                val sy = y + dy
                val t = tile(sx, sy) ?: continue
                val i = (sy - originY) * width + (sx - originX)
                if (passable(t) && !reached[i]) { reached[i] = true; stack.addLast(i) }
            }
        }
        warps.forEach { seed(it.x, it.y) }
        triggerWarps.forEach { seed(it.x, it.y) }
        scriptWarps.forEach { seed(it.x, it.y) }
        triggers.forEach { seed(it.x, it.y) }
        people.forEach { seed(it.x, it.y) }
        signs.forEach { seed(it.x, it.y) }
        // An area without any event (a map built by hand, in tests) has no place to tell the void from: all inside.
        if (stack.isEmpty()) return@lazy BooleanArray(width * height) { true }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = originX + i % width
            val y = originY + i / width
            for ((dx, dy) in SEED_AROUND) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                val t = tile(nx, ny) ?: continue
                val n = (ny - originY) * width + (nx - originX)
                if (!reached[n] && passable(t)) { reached[n] = true; stack.addLast(n) }
            }
        }
        reached
    }

    private companion object {
        /** A tile and its four neighbours. */
        val SEED_AROUND = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)

        /** Tiles a player may cross somehow (walking, surfing, jumping, climbing, through a door, carried over lava). */
        fun passable(t: TileInfo): Boolean = when (t.kind) {
            TileKind.Wall, TileKind.Pc, TileKind.Counter -> false
            TileKind.Door, TileKind.Ladder, is TileKind.Water, TileKind.Waterfall, TileKind.Whirlpool, is TileKind.RockClimb, TileKind.Lava,
            is TileKind.Ledge, is TileKind.Bridge -> true
            else -> !t.blocked
        }
    }
}

/** What an [Area] is: a coordinate space several maps share, or one map loaded alone. */
enum class AreaKind {
    /**
     * Several maps in one coordinate space, walked into each other (the Gen 4 overworld matrix: every route and town
     * of the region): no tile of it is ever void ([Area.outside]).
     */
    OVERWORLD,

    /** One map loaded alone (a building, a cave floor, a gym): its block's tiles around the room are the void. */
    SINGLE_MAP,
}

/** One tile: collision, what it is, and the heights of the surfaces on it (several on bridges). */
data class TileInfo(
    val blocked: Boolean,
    val kind: TileKind,
    /** Heights (game units) of the walkable surfaces on this tile; empty when unknown (flat maps). */
    val heights: List<Int> = emptyList(),
) {
    /**
     * Walking here rolls for a wild encounter: tall grass, and the cave floors that have wild Pokémon (the game's
     * encounter tile behaviours that aren't water).
     */
    val landEncounters: Boolean get() = kind == TileKind.TallGrass || kind == TileKind.Cave
}

/** What a tile is, for movement and display. Decoded per game from its tile behaviours. */
sealed interface TileKind {
    data object Floor : TileKind
    data object TallGrass : TileKind
    data object Wall : TileKind
    /** A ledge: can only be jumped in [jump] direction (the player lands 2 tiles further). */
    data class Ledge(val jump: Direction) : TileKind
    /**
     * Water. [wildEncounters]: surfing on it can start a wild battle (most surfable water; not the calm water of a
     * few spots, like the pools of some caves).
     */
    data class Water(val surfable: Boolean, val fishable: Boolean, val wildEncounters: Boolean = surfable) : TileKind
    data object Waterfall : TileKind
    data object Whirlpool : TileKind
    /** Ice: stepping onto it slides the player in the same direction until a tile that isn't ice, or an obstacle. */
    data object Ice : TileKind
    data object Lava : TileKind
    data object Ladder : TileKind
    data object Counter : TileKind

    /** A PC (Pokémon storage, item storage): examined with A, never walked on. */
    data object Pc : TileKind
    data object Door : TileKind
    /**
     * A spinning arrow tile (Rocket Hideout): stepping on it pushes the player in [push] direction, tile after tile,
     * over any floor, turning on the next arrows, until a [SpinnerStop] tile or an obstacle.
     */
    data class Spinner(val push: Direction) : TileKind

    /** The tile that ends a [Spinner] push (plain floor otherwise). */
    data object SpinnerStop : TileKind

    /**
     * A rocky wall climbed with Rock Climb, along [axis] only (HGSS `MetatileBehavior_IsRockClimbInDirection`: the
     * north-south walls are climbed facing north or south, the east-west ones facing east or west). Facing the wall,
     * A asks to use Rock Climb; the player then crosses every wall tile in a row that way and lands on the first tile
     * after them, up or down (the same move both ways).
     */
    data class RockClimb(val axis: ClimbAxis) : TileKind
    data object Sand : TileKind
    data object Cave : TileKind

    /**
     * A bridge tile. The player gets on a bridge by stepping on a [start] tile and stays on it while walking bridge
     * tiles; a bridge [overWater] is walkable floor for a player on the bridge and water (surfed under) otherwise.
     */
    data class Bridge(val overWater: Boolean = false, val start: Boolean = false) : TileKind

    /** Floor with a railing (or a raised edge) on [blockedSides]: the player can't cross those sides of the tile. */
    data class Railing(val blockedSides: Set<Direction>) : TileKind
    /** A behaviour the decoder doesn't know (kept raw for diagnostics). */
    data class Unknown(val behavior: Int) : TileKind
}

/** The directions a [TileKind.RockClimb] wall is climbed in. */
enum class ClimbAxis(val directions: Set<Direction>) {
    /** Climbed going north (up the screen) or south. */
    NORTH_SOUTH(setOf(Direction.NORTH, Direction.SOUTH)),

    /** Climbed going east or west. */
    EAST_WEST(setOf(Direction.EAST, Direction.WEST)),
    ;

    /** True when a player moving [direction] climbs a wall of this axis. */
    fun allows(direction: Direction): Boolean = direction in directions
}

/**
 * The field moves of the Gen 4 games, used outside battle from the party menu ([dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]
 * says which ones a game has and what they need): those that open a way on the map (walks use them by themselves,
 * [FieldMoveUse.ROUTE]), [FLY] (the `fly` action), and the others (the `use_field_move` action, [FieldMoveUse.ACTION]).
 */
enum class FieldMoveKind(val use: FieldMoveUse) {
    SURF(FieldMoveUse.ROUTE),
    CUT(FieldMoveUse.ROUTE),
    ROCK_SMASH(FieldMoveUse.ROUTE),

    /** Pushes boulders. */
    STRENGTH(FieldMoveUse.ROUTE),
    WHIRLPOOL(FieldMoveUse.ROUTE),
    WATERFALL(FieldMoveUse.ROUTE),
    ROCK_CLIMB(FieldMoveUse.ROUTE),

    /** Flies to a town already visited (the `fly` action): opens no way on a map, routes never use it. */
    FLY(FieldMoveUse.FLY),

    /** Lights up a dark cave. */
    FLASH(FieldMoveUse.ACTION),

    /** Back to the last Pokémon Center used (where the game allows it). */
    TELEPORT(FieldMoveUse.ACTION),

    /** Out of a cave to its entrance (where the game allows it). */
    DIG(FieldMoveUse.ACTION),

    /** Lures a wild Pokémon (a battle starts where they appear). */
    SWEET_SCENT(FieldMoveUse.ACTION),

    /** Gives some of the user's HP to another Pokémon of the party. */
    MILK_DRINK(FieldMoveUse.ACTION),

    /** Gives some of the user's HP to another Pokémon of the party. */
    SOFTBOILED(FieldMoveUse.ACTION),

    /** Shakes the tree the player faces (HeartGold / SoulSilver): a wild Pokémon may fall. */
    HEADBUTT(FieldMoveUse.ACTION),

    /** Plays the recorded cry (Chatot). */
    CHATTER(FieldMoveUse.ACTION),

    /** Clears the fog of the map (Platinum). */
    DEFOG(FieldMoveUse.ACTION),
    ;

    /** Language-independent id of the move on the wire (`teleport`, `sweet_scent`): what `use_field_move` takes. */
    val wire: String get() = name.lowercase()

    /**
     * Id of its entry in a Pokémon's party menu (`fieldmove:teleport`), as the party menu decoders name it. A game
     * whose party menu isn't decoded yet has every field move [FieldMoveAccess.NotSupported] instead (never tried).
     */
    val menuEntry: String get() = "fieldmove:" + name.lowercase().replace("_", "")

    /** True for the moves that give HP to another Pokémon of the party ([MILK_DRINK], [SOFTBOILED]): a target is chosen. */
    val healsAnother: Boolean get() = this == MILK_DRINK || this == SOFTBOILED

    companion object {
        /** The move of wire id [wire] ([FieldMoveKind.wire], case and spaces ignored), or null. */
        fun parse(wire: String): FieldMoveKind? {
            val key = wire.trim().lowercase().removePrefix("fieldmove:").replace(" ", "_").replace("-", "_")
            return entries.firstOrNull { it.wire == key || it.menuEntry == "fieldmove:$key" }
        }
    }
}

/** How a [FieldMoveKind] is used. */
enum class FieldMoveUse {
    /** Opens a way on the map: walks use it by themselves when the party can. */
    ROUTE,

    /** The `fly` action. */
    FLY,

    /** The `use_field_move` action. */
    ACTION,
}

/** A warp: on (x, y), what its [trigger] asks (stepping on it, or pressing a direction on it) leads to [targetZone]. */
data class Warp(
    val zone: Int,
    val id: Int,
    val x: Int,
    val y: Int,
    val targetZone: Int,
    val targetWarp: Int,
    val trigger: WarpTrigger = WarpTrigger.Enter,
    /**
     * Where the game moves the player once arrived on this warp's tile, when it does: one tile off a ladder (HGSS: up
     * the ladder onto the floor above, the player stands north of the hole it came out of; down onto a ladder's foot,
     * south of it, measured on the bench in the Bell Tower). Null when the player stays on the tile (or unknown).
     */
    val arrivalStep: Direction? = null,
)

/**
 * What takes a warp, from the game's rules for the behaviour of its tile. The Gen 4 engine checks warps twice
 * (pokeheartgold / pokeplatinum src/field/field_control.c): at the end of every step onto a tile
 * (`FieldSystem_CheckTransition`: north entrances, warp panels, ladders down, escalators) and when a direction is
 * pressed (`FieldSystem_CheckMapTransition`: a door ahead, or the mat / stairs / ladder the player stands on, for its
 * own direction only). A warp on a tile with neither behaviour is never taken: only arrived at.
 *
 * Why it matters for walks: an [Enter] warp can't be crossed (and, standing on it after arriving, only stepping off and
 * back on takes it: pressing does nothing), while a [Press] tile is plain floor to walk across or stop on, as long as
 * the player doesn't leave it towards its direction (the "doormat below a door" of the shuffled warps).
 */
sealed interface WarpTrigger {
    /** Entering the tile, from any side, takes it: doors (walked into), warp panels, north entrances, ladders down. */
    data object Enter : WarpTrigger

    /**
     * Standing on the tile, a press towards [direction] takes it: exit mats, side stairs, the ladders climbed north or
     * south (a ladder down a hole is [Enter]).
     */
    data class Press(val direction: Direction) : WarpTrigger

    /** Nothing takes it: its tile has no warp behaviour, the warp is only where the other side's warp arrives. */
    data object Never : WarpTrigger
}

/** A sign / examinable object read with A, or an item hidden on the ground ([kind]). */
data class Sign(
    val zone: Int,
    val id: Int,
    val x: Int,
    val y: Int,
    val script: Int,
    val kind: SignKind = SignKind.SIGN,
    /** For a [SignKind.HIDDEN_ITEM]: the event flag set once it's picked up. */
    val flag: Int? = null,
)

/** What a [Sign] (a background event) is. */
enum class SignKind {
    /** Read with A (signs, notices, furniture with a script). */
    SIGN,

    /** An invisible item found with A (or the Dowsing Machine); gone once its flag is set. */
    HIDDEN_ITEM,
}

/** A person or object placed by the map's events (its live position may differ: read the RAM). */
data class PersonTemplate(
    val zone: Int,
    val id: Int,
    val sprite: Int,
    val x: Int,
    val y: Int,
    val facing: Direction?,
    /** Sight range for trainers (0 = not a trainer). */
    val sightRange: Int,
    val script: Int,
    /** Flag hiding this person once set (e.g. after an event), or 0. */
    val hiddenByFlag: Int,
    /** For an obstacle object (Cut tree, Rock Smash rock, Strength boulder): the field move that clears it. */
    val obstacle: FieldMoveKind? = null,
    /**
     * For a trainer of a common trainer script: the event flag the game sets once it is beaten (its trainer flag), so
     * a trainer of a map the player isn't on is known beaten or not from the save. Null otherwise (not a trainer, or
     * a scripted battle that records its win its own way).
     */
    val trainerFlag: Int? = null,
    /** True when it walks around on its own (a wander range): where the map places it isn't where it stands. */
    val wanders: Boolean = false,
    /**
     * The directions it turns to on its own besides [facing] (its movement type: a trainer looking around, or left and
     * right): a trainer may see the player along any of them. Empty when it keeps facing one way.
     */
    val looks: Set<Direction> = emptySet(),
    /**
     * True when a script of its map moves it elsewhere (the map's entry script putting beaten trainers out of the
     * way: the Cinnabar Gym's): where the map places it isn't where it stands once the player is there. False when no
     * script does, or when the game's scripts aren't read.
     */
    val scriptMoved: Boolean = false,
) {
    /**
     * True when it is there now by the save's event [flags]: always without a flag, else while its flag is clear.
     * Unknown flags (a game that doesn't read them) count it as absent: nothing is called blocked or one way on a guess.
     */
    fun presentWith(flags: dev.kotlinds.pokemonclient.state.EventFlags?): Boolean =
        hiddenByFlag == 0 || flags?.get(hiddenByFlag) == false

    /** How far it watches for the player by the save's [flags]: 0 once its [trainerFlag] says it was beaten. */
    fun sightWith(flags: dev.kotlinds.pokemonclient.state.EventFlags?): Int =
        if (trainerFlag != null && flags?.get(trainerFlag) == true) 0 else sightRange
}

/** A coordinate trigger: stepping on it runs [script] while variable [variable] equals [value]. */
data class Trigger(
    val zone: Int,
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val script: Int,
    val variable: Int,
    val value: Int,
    /** Its script does nothing (a placeholder ending at once): stepping on it starts no scene, it is walked like floor. */
    val inert: Boolean = false,
    /**
     * When this holds, its script ends at once without showing anything (no message, no movement): armed or not, the
     * trigger is walked like floor. The Viridian Gym's guide trigger (scr_seq_T02GYM0101_003) re-arms on every entry
     * (`VAR_UNK_4127` reset to 0 by the map's init script), but once his speech was heard (`FLAG_UNK_13A`) it only
     * sets the variable back and releases the player. Null when its script always shows something (or is unknown).
     */
    val quietWhen: FlagCondition? = null,
) {
    /**
     * True when stepping on it now runs a scene the player sees: its variable ([variable] read through [variableOf])
     * has the awaited [value], and its script isn't in its [quietWhen] silent case ([flagOf] reads the flags). A flag
     * the game can't read (null) never makes it quiet: better stop on a silent trigger than walk into a scene.
     */
    fun startsScene(variableOf: (Int) -> Int?, flagOf: (Int) -> Boolean?): Boolean =
        variableOf(variable) == value && quietWhen?.let { flagOf(it.flag) == it.set } != true

    /** The tiles it covers: its rectangle ([width] × [height], at least one tile). */
    val tiles: List<Pair<Int, Int>> get() = (x until x + maxOf(1, width)).flatMap { tx -> (y until y + maxOf(1, height)).map { ty -> tx to ty } }

    /** True when it covers ([tx], [ty]). */
    fun covers(tx: Int, ty: Int): Boolean = tx in x until x + maxOf(1, width) && ty in y until y + maxOf(1, height)
}

/** An event flag of the game's save having a given state: [flag] is set when [set], clear otherwise. */
data class FlagCondition(val flag: Int, val set: Boolean)

/**
 * A coordinate trigger ([Trigger] number [trigger] of [zone]) whose script warps the player to ([x], [y]) on the same
 * zone: a warp pad or a trap tile that the map's warp list doesn't show. Active while the trigger is.
 */
data class ScriptWarp(val zone: Int, val trigger: Int, val x: Int, val y: Int, val facing: Direction?)

/**
 * A coordinate trigger ([Trigger] number [trigger] of [zone]) at ([x], [y]) whose script moves the player to
 * ([toX], [toY]) on another zone [targetZone]: a hole the player falls through to the floor below (Victory Road,
 * Ice Path). One way; active while the trigger is.
 */
data class TriggerWarp(val zone: Int, val trigger: Int, val x: Int, val y: Int, val targetZone: Int, val toX: Int, val toY: Int)
