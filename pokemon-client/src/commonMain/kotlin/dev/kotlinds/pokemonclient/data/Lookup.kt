package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import dev.kotlinds.pokemonclient.state.normalizeName
import dev.kotlinds.pokemonclient.world.EncounterTables

/**
 * How much the agent may know beyond what the game shows. It changes what is **shown**, never what is computed.
 */
enum class KnowledgeLevel(val description: String) {
    /** Only what the game displays (and what a human sees on screen). */
    NONE("Only what the game shows."),

    /** Plus the Pokédex: species and move sheets, the type chart, estimated effectiveness in battle. */
    POKEDEX("Plus Pokémon and move sheets, the type chart and estimated effectiveness in battle."),

    /**
     * Plus a walkthrough: trainers' teams, encounters, map events, the next goal and why a way is blocked, and what the
     * player hasn't seen (hidden items, teleport tiles never on screen, hidden switches: see view.Sightings).
     */
    POKEDEX_PLUS_WALKTHROUGH("Plus trainers' teams, encounters, the next goal and why a way is blocked, hidden items and unseen teleports."),
    ;

    /** True when this level shows what [required] shows. */
    fun allows(required: KnowledgeLevel) = ordinal >= required.ordinal
}

/** What `lookup` can be asked about. */
enum class LookupKind(val required: KnowledgeLevel, val description: String) {
    /** Items: a human reads their description in the bag and their price in shops. */
    ITEM(KnowledgeLevel.NONE, "an item: pocket, price"),
    SPECIES(KnowledgeLevel.POKEDEX, "a Pokémon species: types, base stats, abilities, evolutions"),
    MOVE(KnowledgeLevel.POKEDEX, "a move: type, category, power, accuracy, PP, priority"),
    LEARNSET(KnowledgeLevel.POKEDEX, "the moves a species learns by level"),
    MACHINE(KnowledgeLevel.POKEDEX, "a TM / HM: the move it teaches"),
    TYPE(KnowledgeLevel.POKEDEX, "an attacking type: what it is super effective / not very effective / useless against"),

    /**
     * The wild Pokémon of a map. At the Pokédex level only the species the player's Pokédex has seen are named (the
     * others are counted: `unseen`); with a walkthrough, every one.
     */
    ENCOUNTERS(KnowledgeLevel.POKEDEX, "the wild Pokémon of a map (id: a map's name or map:<id>; empty or `here`: the current map): by way (walk, surf, " +
        "rock smash, rods) and time of day, with chances and levels; at the Pokédex level only the species already seen"),
}

/**
 * What `lookup encounters` needs besides the game data: the [world]'s tables ([WorldSource.wildEncounters]), the map
 * names, where the player is ([currentZone]) and the species the player's Pokédex has seen ([seen]: null when unknown,
 * then nothing is named below the walkthrough level).
 */
class EncounterContext(
    val world: dev.kotlinds.pokemonclient.world.WorldSource,
    val mapName: (Int) -> dev.kotlinds.pokemonclient.state.MapName,
    val currentZone: Int?,
    val seen: Set<SpeciesId>?,
)

/**
 * Answers `lookup(kind, id)` from the [GameData], within the [KnowledgeLevel]. Ids are the typed ones (`species:25`,
 * `move:85`, `item:17`, `tm01`, `type:fire`); a name is accepted too ("Pikachu", "Thunderbolt", accents and case
 * ignored), as a convenience.
 */
class Lookup(private val data: GameData, private val level: KnowledgeLevel, private val encounters: EncounterContext? = null) {

    /** The answer, or a failure whose message says why (unknown id, not allowed at this knowledge level). */
    fun lookup(kind: LookupKind, id: String): Result<JsonObject> = runCatching {
        require(level.allows(kind.required)) { "$kind needs the knowledge level ${kind.required} (the agent plays with $level)" }
        when (kind) {
            LookupKind.SPECIES -> species(findSpecies(id))
            LookupKind.LEARNSET -> learnset(findSpecies(id))
            LookupKind.MOVE -> move(findMove(id))
            LookupKind.ITEM -> item(findItem(id))
            LookupKind.MACHINE -> machine(id)
            LookupKind.TYPE -> type(findType(id))
            LookupKind.ENCOUNTERS -> encounters(id)
        }
    }

    /**
     * The wild Pokémon of the map [id] names (`map:<id>`, a map's name or its place: "Route 32", "Union Cave"; empty or
     * `here`: the current map), every matching map with encounters: one entry per way of meeting them
     * ([dev.kotlinds.pokemonclient.world.EncounterGroup]). Below the walkthrough level, only the species seen are
     * named ([EncounterContext.seen]); `unseen` counts the share of a group's encounters left out.
     */
    private fun encounters(id: String): JsonObject {
        val context = encounters ?: throw IllegalArgumentException("No encounter tables for this game")
        val world = context.world
        val query = id.trim()
        val zones = when {
            query.isEmpty() || query.equals("here", ignoreCase = true) ->
                listOf(context.currentZone ?: throw IllegalArgumentException("The player isn't on a map: give a map's name"))
            else -> {
                val all = 0 until world.zoneCount
                all.filter { context.mapName(it).isNamed(query) }.ifEmpty { all.filter { context.mapName(it).placeIs(query) } }
                    .ifEmpty { throw IllegalArgumentException("Unknown map `$id` (a map's name as the state shows it, or map:<id>)") }
            }
        }
        val walkthrough = level.allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)
        val seen = context.seen.orEmpty()
        // Unknown tables say nothing per map: never "no wild Pokémon" (they may appear on any grass, cave floor or water).
        if (world.encounterTables == EncounterTables.UNKNOWN) return buildJsonObject {
            put("tables", EncounterTables.UNKNOWN.name.lowercase())
            putJsonArray("maps") {}
            put("note", "no encounter tables for this game yet: wild Pokémon may appear on any tall grass, cave floor or water of ${zones.joinToString { context.mapName(it).toString() }}")
        }
        val tables = zones.mapNotNull { world.wildEncounters(it) }
        return buildJsonObject {
            put("tables", EncounterTables.DECODED.name.lowercase())
            put("knowledge", if (walkthrough) "every species" else "only the species your Pokédex has seen")
            putJsonArray("maps") {
                if (tables.isEmpty()) return@putJsonArray
                tables.forEach { table ->
                    add(buildJsonObject {
                        put("map", context.mapName(table.zoneId).toString())
                        put("id", dev.kotlinds.pokemonclient.state.MapName.idForm(table.zoneId))
                        putJsonArray("groups") {
                            table.groups.forEach { group ->
                                val shown = group.slots.filter { walkthrough || it.species in seen }
                                add(buildJsonObject {
                                    put("method", group.method.name.lowercase())
                                    group.time?.let { put("time", it.name.lowercase()) }
                                    group.condition?.let { put("only_when", it.name.lowercase()) }
                                    group.rate?.let { put("rate", it) }
                                    putJsonArray("species") {
                                        shown.forEach { slot ->
                                            add(kotlinx.serialization.json.JsonPrimitive(
                                                "species:${slot.species.value} ${data.species(slot.species)?.name ?: "?"} ${slot.chance}% Lv" +
                                                    if (slot.levels.first == slot.levels.last) "${slot.levels.first}" else "${slot.levels.first}-${slot.levels.last}",
                                            ))
                                        }
                                    }
                                    val hidden = group.slots - shown.toSet()
                                    if (hidden.isNotEmpty()) put("unseen", "${hidden.size} species not seen yet (${hidden.sumOf { it.chance }}% of these encounters)")
                                })
                            }
                        }
                    })
                }
            }
            if (tables.isEmpty()) put("note", "no wild Pokémon on ${zones.joinToString { context.mapName(it).toString() }}")
        }
    }

    private fun species(info: SpeciesInfo) = buildJsonObject {
        put("id", "species:${info.id.value}")
        put("name", info.name)
        putJsonArray("types") { info.types.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.label)) } }
        putJsonObject("base_stats") {
            put("hp", info.baseStats.hp); put("attack", info.baseStats.attack); put("defense", info.baseStats.defense)
            put("sp_attack", info.baseStats.spAttack); put("sp_defense", info.baseStats.spDefense); put("speed", info.baseStats.speed)
            put("total", info.baseStats.total)
        }
        putJsonArray("abilities") { info.abilities.mapNotNull(data::abilityName).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("catch_rate", info.catchRate)
        info.growthRate?.let { rate ->
            putJsonObject("growth_rate") {
                put("curve", rate.name.lowercase())
                put("exp_to_level_50", ExpCurves.expForLevel(rate, 50))
                put("exp_to_level_100", ExpCurves.expForLevel(rate, ExpCurves.MAX_LEVEL))
            }
        }
        // The TMs / HMs it can learn, with their moves.
        putJsonArray("machines") {
            info.machines.sortedBy { it.number }.forEach { machine ->
                val move = data.machineMove(machine)
                add(kotlinx.serialization.json.JsonPrimitive(machine.label + (move?.let { " " + (data.move(it)?.name ?: "move:${it.value}") } ?: "")))
            }
        }
        putJsonArray("evolutions") {
            info.evolutions.forEach { evo ->
                add(buildJsonObject {
                    put("to", data.species(evo.target)?.name ?: "species:${evo.target.value}")
                    put("method", evo.method.name.lowercase())
                    put("param", evo.param)
                })
            }
        }
    }

    private fun learnset(info: SpeciesInfo) = buildJsonObject {
        put("species", info.name)
        put("moves", buildJsonArray {
            data.learnset(info.id).forEach { lm ->
                add(buildJsonObject {
                    put("level", lm.level)
                    put("move", data.move(lm.move)?.name ?: "move:${lm.move.value}")
                    put("id", "move:${lm.move.value}")
                })
            }
        })
    }

    private fun move(info: MoveInfo) = buildJsonObject {
        put("id", "move:${info.id.value}")
        put("name", info.name)
        put("type", info.type.label)
        put("category", info.category.label)
        put("power", info.power)
        put("accuracy", info.accuracy)
        put("pp", info.pp)
        put("priority", info.priority)
        if (info.effectChance > 0) put("effect_chance", info.effectChance)
    }

    private fun item(info: ItemInfo) = buildJsonObject {
        put("id", "item:${info.id.value}")
        put("name", info.name)
        info.pocket?.let { put("pocket", it.name.lowercase()) }
        put("price", info.price)
        data.machineOf(info.id)?.let { machine -> data.machineMove(machine)?.let { put("teaches", data.move(it)?.name) } }
    }

    private fun machine(id: String): JsonObject {
        val number = Regex("(tm|hm)\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(id)
            ?: throw IllegalArgumentException("A machine is TM01..TM92 or HM01..HM08, not `$id`")
        val machine = MachineId(if (number.groupValues[1].lowercase() == "hm") MachineId.TM_COUNT + number.groupValues[2].toInt() else number.groupValues[2].toInt())
        val move = data.machineMove(machine)?.let(data::move) ?: throw IllegalArgumentException("No ${machine.label}")
        return buildJsonObject {
            put("machine", machine.label)
            put("move", move(move))
        }
    }

    private fun type(attacking: PokemonType) = buildJsonObject {
        put("type", attacking.label)
        val against = PokemonType.entries.groupBy { data.typeChart.effectiveness(attacking, it) }
        for ((effectiveness, types) in against) {
            if (effectiveness == Effectiveness.NORMAL) continue
            putJsonArray(effectiveness.name.lowercase()) { types.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.label)) } }
        }
    }

    // region Ids and names

    private fun findSpecies(raw: String): SpeciesInfo {
        val name = { id: Int -> data.species(SpeciesId(id))?.name }
        return idOrName(raw, "species", 1..data.speciesCount, name)?.let { data.species(SpeciesId(it)) }
            ?: throw unknown("species", raw, 1..data.speciesCount, name)
    }

    private fun findMove(raw: String): MoveInfo {
        val name = { id: Int -> data.move(MoveId(id))?.name }
        return idOrName(raw, "move", 1..data.moveCount, name)?.let { data.move(MoveId(it)) }
            ?: throw unknown("move", raw, 1..data.moveCount, name)
    }

    private fun findItem(raw: String): ItemInfo {
        val name = { id: Int -> data.item(ItemId(id))?.name }
        return idOrName(raw, "item", 1..data.itemCount, name)?.let { data.item(ItemId(it)) }
            ?: throw unknown("item", raw, 1..data.itemCount, name)
    }

    /**
     * "Unknown item `Revve`. Close matches: item:28 Revive, ..." — the names nearest to [raw] (names containing it,
     * then by edit distance on the normalized spelling), so the agent can retry with a valid id.
     */
    private fun unknown(prefix: String, raw: String, range: IntRange, name: (Int) -> String?): IllegalArgumentException {
        val wanted = normalizeName(raw.removePrefix("$prefix:"))
        val close = if (wanted.isEmpty()) emptyList() else range.asSequence()
            .mapNotNull { id -> name(id)?.takeIf { it.isNotBlank() }?.let { id to it } }
            .mapNotNull { (id, label) ->
                val n = normalizeName(label).takeIf { it.length >= MIN_NAME_LENGTH } ?: return@mapNotNull null
                val score = if (n.contains(wanted) || wanted.contains(n)) 0 else editDistance(n, wanted)
                Triple(id, label, score)
            }
            .filter { it.third <= maxOf(2, wanted.length / 3) }
            .sortedWith(compareBy({ it.third }, { it.first }))
            .take(MAX_SUGGESTIONS)
            .map { (id, label) -> "$prefix:$id $label" }
            .toList()
        val suffix = if (close.isEmpty()) "" else ". Close matches: ${close.joinToString()}"
        return IllegalArgumentException("Unknown $prefix `$raw`$suffix")
    }

    /** Levenshtein distance between two short strings. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun findType(raw: String): PokemonType {
        val value = normalizeName(raw.removePrefix("type:"))
        return PokemonType.entries.firstOrNull { normalizeName(it.label) == value || normalizeName(it.name) == value }
            ?: throw IllegalArgumentException("Unknown type `$raw` (one of ${PokemonType.entries.joinToString { it.label }})")
    }

    /** `prefix:<number>`, a bare number, or a name found among [range] with [name]. */
    private fun idOrName(raw: String, prefix: String, range: IntRange, name: (Int) -> String?): Int? {
        raw.removePrefix("$prefix:").toIntOrNull()?.let { return it.takeIf { id -> id in range } }
        val wanted = normalizeName(raw)
        return range.firstOrNull { name(it)?.let(::normalizeName) == wanted }
    }

    // endregion

    private companion object {
        const val MAX_SUGGESTIONS = 5

        /** Placeholder names ("???", "-") are never suggested. */
        const val MIN_NAME_LENGTH = 2
    }
}
