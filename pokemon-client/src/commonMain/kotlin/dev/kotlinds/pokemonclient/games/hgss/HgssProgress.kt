package dev.kotlinds.pokemonclient.games.hgss


/**
 * The early-game story flags and scene vars (HeartGold, new game until the first gym) that [HgssStoryTable]'s first
 * steps check, read into [StoryInfo] by the reader ([HgssReader.STORY_FLAGS]).
 *
 * Ids come from include/constants/flags.h / vars.h of pokeheartgold and the scripts that set them
 * (files/fielddata/script/scr_seq: 0845_T20R0201 player house 1F, 0843_T20R0101 Elm's lab, 0842_T20 New Bark,
 * 0850_T21 Cherrygrove, 0227_R30 Route 30, 0229_R30R0201 Mr. Pokémon's house, 0225_R29, 0018_D15R0103 Sprout Tower,
 * 0859_T22GYM0101 Violet Gym, 0858_T22FS0101 Violet Mart). Scene vars only increase in this part of the game, so
 * they are compared with >=.
 */
object HgssProgress {

    // Flags (SaveVarsFlags.flags, bit id%8 of byte id/8)
    const val FLAG_GOT_BAG = 0x11B              // Mom's opening talk done (Bag, Trainer Card, Save, Options)
    const val FLAG_GOT_STARTER = 0x6A
    const val FLAG_GOT_POKEDEX = 0x6B           // Oak at Mr. Pokémon's (same scene as the Mystery Egg)
    const val FLAG_BEAT_SPROUT_ELDER = 0x76     // FLAG_UNK_076: beat Elder Li (opens the Violet Gym)
    const val FLAG_GAVE_EGG_TO_ELM = 0x79       // FLAG_GAVE_RIVAL_NAME_TO_OFFICER: police scene done, egg given
    const val FLAG_RIVAL_CHERRYGROVE = 0x99     // FLAG_MET_PASSERBY_BOY: rival battle in Cherrygrove done
    const val FLAG_CATCHING_TUTORIAL = 0x9A     // Lyra's catching tutorial on Route 29 done
    const val FLAG_GOT_POKEGEAR = 0x9C
    const val FLAG_ELM_PANIC_CALL = 0xEE        // Elm's "Pokémon stolen" call on Route 30
    const val FLAG_MET_ELM = 0x160              // FLAG_ELMS_LAB_PREVENT_PLAYER_ESCAPE: Elm's intro done, starter not chosen

    // Vars (SaveVarsFlags.vars[id - 0x4000])
    const val VAR_NEW_BARK_TOWN = 0x4072        // 1 starter chosen, 2 Lyra's scene after the lab, 3 egg returned
    const val VAR_CHERRYGROVE = 0x4073          // 1 Guide Gent tour (running shoes), 2 Map Card, 3 rival armed, 4 rival battled
    const val VAR_NEW_BARK_WEST_EXIT = 0x407E   // 1 Elm registered his number at the west exit
    const val VAR_ROUTE_30 = 0x408C             // 1 Apricorn Box, 2 left Mr. Pokémon's, 3 Elm's panic call
    const val VAR_PLAYERS_HOUSE_1F = 0x4106     // 1 Mom's talk done, 2 Lyra's scene outside the house done
    const val VAR_MR_POKEMONS_HOUSE = 0x4107    // 1 got egg + Pokédex, 2 egg returned
    const val VAR_ELMS_LAB = 0x4108             // 1 starter, 2 Potions, 3 egg+dex, 4 egg given, 5 aide, 6 Falkner, 7 Togepi egg

    val FLAGS = listOf(
        FLAG_GOT_BAG, FLAG_GOT_STARTER, FLAG_GOT_POKEDEX, FLAG_BEAT_SPROUT_ELDER,
        FLAG_GAVE_EGG_TO_ELM, FLAG_RIVAL_CHERRYGROVE, FLAG_CATCHING_TUTORIAL, FLAG_GOT_POKEGEAR, FLAG_ELM_PANIC_CALL, FLAG_MET_ELM,
    )
    val VARS = listOf(
        VAR_NEW_BARK_TOWN, VAR_CHERRYGROVE, VAR_NEW_BARK_WEST_EXIT, VAR_ROUTE_30,
        VAR_PLAYERS_HOUSE_1F, VAR_MR_POKEMONS_HOUSE, VAR_ELMS_LAB,
    )
}
