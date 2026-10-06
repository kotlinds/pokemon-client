package dev.kotlinds.pokemonclient.state

/**
 * The name of one map (zone), the same model for every game ([dev.kotlinds.pokemonclient.PokemonGame.mapName]): the
 * state, `go_to`, the exits of the map view and the error suggestions all show and match this one name.
 *
 * Two parts, either may be unknown:
 * - [location]: the place name the game shows on entering ("New Bark Town", "Bourg Geon" in French), read from the
 *   ROM's text: shared by a town and its buildings;
 * - [map]: the map's own name ("New Bark Player House 2F"), the developers' name from the decompilation (English,
 *   not in the ROM): tells the maps of one place apart.
 *
 * The id is [id], never part of a name: a name without any known part shows the id form `map:<id>`, which `go_to`
 * accepts as well (the one fallback of every game).
 */
data class MapName(val id: Int, val location: String? = null, val map: String? = null) {

    /**
     * The display form: "New Bark Town (New Bark Player House 2F)"; the location alone when the map's own name says
     * the same ("Route 29", "New Bark Town" for the map "New Bark"); whichever part is known; else `map:<id>`.
     */
    override fun toString(): String = when {
        location != null && map != null && !sameMapName(location, map) -> "$location ($map)"
        else -> location ?: map ?: idForm(id)
    }

    /** The place alone, as town maps label it ("Ecruteak City"): [location] when known, else the display form. */
    val place: String get() = location ?: toString()

    /**
     * True when [query] names this map exactly ([sameMapName]): its own name, its display form ("Violet City (Violet
     * Gym)", as the state shows it), `map:<id>`, or a form earlier versions showed ([isFormerForm]: an agent's notes
     * keep them). The place alone ([location]) is [placeIs]: a town's buildings share it.
     */
    fun isNamed(query: String): Boolean =
        parseIdForm(query)?.let { it == id } ?: (map?.let { sameMapName(it, query) } == true || sameMapName(toString(), query) || isFormerForm(query))

    /**
     * The display forms of earlier versions, still accepted so that names noted then keep working:
     * - "location (map)" even when both parts say the same ("Route 29 (Route 29)", "New Bark Town (New Bark)"),
     *   which the display form now shows once;
     * - "location #id" (Platinum's maps before they had their own names: "Twinleaf Town #411"), matched by the id
     *   (the location part, in the game's language, must be this map's place too).
     */
    private fun isFormerForm(query: String): Boolean {
        if (location != null && map != null && sameMapName("$location ($map)", query)) return true
        val numbered = FORMER_ID_FORM.matchEntire(query.trim()) ?: return false
        return numbered.groupValues[2].toIntOrNull() == id && (location == null || placeIs(numbered.groupValues[1]))
    }

    /** True when [query] is this map's place ([location], e.g. "Bourg Geon"): the town itself and every building of it. */
    fun placeIs(query: String): Boolean = location?.let { sameMapName(it, query) } == true

    companion object {
        /** "Twinleaf Town #411": the place then `#<id>` ([isFormerForm]). */
        private val FORMER_ID_FORM = Regex("(.*?)\\s*#(\\d+)")

        /** `map:<id>`: the id form of a map, accepted wherever a map's name is. */
        fun idForm(id: Int): String = "map:$id"

        /** The map id of [value] when it is the id form `map:<id>`, else null. */
        fun parseIdForm(value: String): Int? = value.takeIf { it.startsWith("map:") }?.removePrefix("map:")?.toIntOrNull()

        /**
         * True when [a] and [b] name the same map: compared by [normalizeName] (case, spaces, punctuation and
         * accents ignored), a "Town" / "City" one of them adds left out (the town is "New Bark Town" on screen, its
         * map "New Bark").
         */
        fun sameMapName(a: String, b: String): Boolean = key(a) == key(b)

        /** [normalizeName] without a trailing "town" / "city". */
        internal fun key(value: String): String = normalizeName(value).removeSuffix("town").removeSuffix("city")
    }
}
