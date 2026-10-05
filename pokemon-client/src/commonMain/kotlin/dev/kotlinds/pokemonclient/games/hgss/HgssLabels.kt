package dev.kotlinds.pokemonclient.games.hgss

/** Human-readable names of the people on the map (from their sprite), for display only. */
object HgssLabels {

    /** Sprites that always show the same character (include/constants/sprites.h). */
    private val namedSprites = mapOf(
        "GSMAMA" to "Mom",
        "DOCTOR" to "Prof. Elm",
        "OOKIDO" to "Prof. Oak",
        "GSRIVEL" to "red-haired boy",
        // Lab-coat sprites: Elm's aide wears ASSISTANTM, but so do the Rocket hideout's and Power Plant's scientists.
        "ASSISTANTM" to "scientist",
        "GSASSISTANTM" to "scientist",
        "ASSISTANTW" to "aide",
        "PCWOMAN1" to "nurse",
        "PCWOMAN2" to "receptionist",
        "PCWOMAN3" to "receptionist",
        "SHOPM1" to "shop clerk",
        "SHOPM1_2" to "shop clerk",
        "SHOPW1" to "shop clerk",
        "POLICEMAN" to "police officer",
        "GSGENTLEMAN" to "gentleman",
        "GENTLEMAN" to "gentleman",
        "MONSTARBALL" to "item ball",
        "ROCK" to "boulder (Strength)",
        "ICE" to "ice block",
        "BREAKROCK" to "cracked rock (Rock Smash)",
        "TREE" to "small tree (Cut)",
        "GSLEADER1" to "Falkner (gym leader)",
        "GSLEADER2" to "Bugsy (gym leader)",
        "GSLEADER3" to "Whitney (gym leader)",
        "GSLEADER4" to "Morty (gym leader)",
        "GSLEADER5" to "Chuck (gym leader)",
        "GSLEADER6" to "Jasmine (gym leader)",
        "GSLEADER7" to "Pryce (gym leader)",
        "GSLEADER8" to "Clair (gym leader)",
        "CHOUROU" to "elder",
        "BOZU" to "sage",
        "KURUMI" to "Kurt",
        "GANTETSU" to "Kurt",
        "SAKAKI" to "Giovanni",
        "WATARU" to "Lance",
        "ROCKETM" to "Team Rocket grunt",
        "ROCKETW" to "Team Rocket grunt",
        "SUNGLASSES" to "man in sunglasses",
        // MYSTERY is the psychic / medium trainer sprite (Psychic Eli on Route 27 uses MYSTERY_2), not a courier.
        "MYSTERY" to "psychic",
        "MYSTERY_2" to "psychic",
        "DELIVERY" to "delivery man",
        "DELIVERY2" to "delivery man",
        "DANCER" to "Kimono Girl",
        "GSBIGFOUR1" to "Will (Elite Four)",
        "GSBIGFOUR2" to "Koga (Elite Four)",
        "GSBIGFOUR3" to "Bruno (Elite Four)",
        "GSBIGFOUR4" to "Karen (Elite Four)",
        // The Kanto leaders aren't in gym order: each gym's leader object (zone events T06/T11/T04/T07/T08/T03GYM0101)
        // tells who wears which sprite (Misty on Route 25 wears GSLEADER11, like in her gym).
        "GSLEADER9" to "Lt. Surge (gym leader)",
        "GSLEADER10" to "Sabrina (gym leader)",
        "GSLEADER11" to "Misty (gym leader)",
        "GSLEADER12" to "Erika (gym leader)",
        "GSLEADER13" to "Janine (gym leader)",
        "GSLEADER14" to "Brock (gym leader)",
        "GSLEADER15" to "Blaine (gym leader)",
        "GSLEADER16" to "Blue (gym leader)",
        "RED" to "Red",
        "MASAKI" to "Bill",
        "MINAKI" to "Eusine",
        "COUNTERM" to "attendant",
        "ITAKO" to "medium",
        "ITAKO_" to "medium",
        "MANIA" to "PokéManiac",
        "JUGGRER" to "juggler",
        "INSTRUCTOR" to "instructor",
        "SUIT" to "man in a suit",
        "CAPTAIN" to "captain",
        "THIEF" to "burglar",
        "FIRE" to "firebreather",
        "BOARDER" to "boarder",
        "SKIERW" to "skier",
        "GANG" to "biker",
        "AMBRELLA" to "parasol lady",
        "GORGGEOUSM" to "rich boy",
        "GORGGEOUSW" to "beauty",
        "USOKKY" to "odd tree (Sudowoodo)",
        "KABIGON" to "Snorlax",
        // Draws nothing (an invisible wall, or a spot that answers A: see HgssExaminables).
        "STOP" to "invisible object",
        "SIGNSHOES" to "sign",
        "SIGNCLOTHES" to "sign",
        "SIGNFLAG" to "sign",
        "SIGNPOKEGEAR" to "sign",
        "SIGNBALL" to "sign",
        "WIFISF" to "attendant",
        "BFSM" to "attendant",
        "BFSW1" to "attendant",
        "BFSW2" to "attendant",
        "LEAG_DOOR2" to "door",
        "BABYBOY1_11" to "door",
        "BABYBOY1_9" to "door",
        "GATE_LEFT" to "shutter",
        "GATE_RIGHT" to "shutter",
        "GATE_TOP" to "shutter",
        "GATE_BOTTOM" to "shutter",
        "GSDSBOY" to "boy",
        "DAIGO" to "Steven",
        "AJI_PERU" to "Persian statue",
        "RKANBUM" to "Team Rocket executive",
        "RKANBUM2" to "Team Rocket executive",
        "RKANBUM3" to "Team Rocket executive",
        "RKANBUW" to "Team Rocket executive",
        "YADON" to "Slowpoke (Pokémon)",
        "RGYARADOSU" to "red Gyarados (Pokémon)",
        "RAPURASU" to "Lapras (Pokémon)",
        "LUG_OBJ01" to "Lugia (Pokémon)",
        "HOU_OBJ01" to "Ho-Oh (Pokémon)",
        "REDMAMA" to "Red's mom",
        "NANAMI" to "Daisy",
        "STOROPHY" to "trophy",
        "GTOROPHY" to "trophy",
        "BTOROPHY" to "trophy",
        "MEDAL" to "medal",
        "SCROLL" to "scroll",
    )

    private val genericWords = listOf(
        "BABYBOY" to "little boy", "BABYGIRL" to "little girl", "MIDDLEMAN" to "middle-aged man",
        "MIDDLEWOMAN" to "middle-aged woman", "OLDMAN" to "old man", "OLDWOMAN" to "old woman", "BIGMAN" to "big man",
        "SWIMMERM" to "swimmer", "SWIMMERW" to "swimmer", "CAMPBOY" to "camper", "PICNICGIRL" to "picnicker",
        "FISHING" to "fisherman", "SEAMAN" to "sailor", "FIGHTER" to "black belt", "WORKMAN" to "worker",
        "COWGIRL" to "cowgirl", "FARMER" to "farmer", "CLOWN" to "clown", "ARTIST" to "artist", "SPORTSMAN" to "sportsman",
        "EXPLORE" to "hiker", "MOUNT" to "hiker", "IDOL" to "idol", "LADY" to "lady", "CYCLEM" to "cyclist", "CYCLEW" to "cyclist",
        "REPORTER" to "reporter", "CAMERAMAN" to "cameraman", "WAITER" to "waiter", "WAITRESS" to "waitress", "MAID" to "maid",
        "BOY" to "boy", "GIRL" to "girl", "WOMAN" to "woman", "MAN" to "man", "BADMAN" to "tough guy",
    )

    private val PLACEHOLDER = Regex("BABYBOY1_\\d+")

    /** "Mom", "woman", "Totodile (Pokémon)"... from the sprite constant name (without the SPRITE_ prefix). */
    fun person(spriteName: String): String {
        namedSprites[spriteName]?.let { return it }
        // Apricorn trees come in one sprite per colour (BONGURI_R, _G, _P...).
        if (spriteName.startsWith("BONGURI")) return "Apricorn tree"
        if (spriteName.startsWith("FOLLOWER_MON_")) {
            // STATIC_: a Pokémon standing on the map (Route 30's Rattata and Pidgey), not a follower.
            val species = spriteName.removePrefix("FOLLOWER_MON_").removePrefix("STATIC_").substringBefore("_F").replace('_', ' ')
            return species.lowercase().replaceFirstChar { it.uppercase() } + " (Pokémon)"
        }
        if (spriteName.startsWith("SPRITE_")) return "person"
        // BABYBOY1_2, _5, _8.._13: placeholder sprites of scripted scenery (doors, statues...), not children.
        if (PLACEHOLDER.matches(spriteName)) return "object"
        val base = spriteName.removePrefix("GS").trimEnd { it.isDigit() || it == '_' }
        return genericWords.firstOrNull { (key, _) -> base == key || base.startsWith(key) }?.second ?: "person"
    }

    /**
     * [objects] with the extra tiles of a big sprite labelled like the object they belong to. A sprite wider than a
     * tile (Snorlax, 2×2) is one object plus placeholders on its other tiles (obj_R11_gsbabyboy1.. with a little boy's
     * sprite, never drawn): same script as the big one, next to it, and movement type [BIG_SPRITE_PART_MOVEMENT].
     */
    fun bigSpriteParts(objects: List<MapObjectInfo>): List<MapObjectInfo> = objects.map { o ->
        if (o.movement != BIG_SPRITE_PART_MOVEMENT || o.scriptId == 0) return@map o
        val owner = objects.firstOrNull { b ->
            b !== o && b.movement != BIG_SPRITE_PART_MOVEMENT && b.scriptId == o.scriptId && b.mapId == o.mapId &&
                kotlin.math.abs(b.x - o.x) <= 1 && kotlin.math.abs(b.z - o.z) <= 1
        } ?: return@map o
        o.copy(label = owner.label)
    }

    /**
     * The movement type of the placeholders filling a big sprite's other tiles: in the ROM's zone events, only the
     * Snorlax tiles of Routes 11 and 12 use it (files/fielddata/eventdata/zone_event 016_R11, 017_R12).
     */
    const val BIG_SPRITE_PART_MOVEMENT = 53
}
