package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4Text
import kotlin.test.Test
import kotlin.test.assertEquals

class HgssTextTest {

    /** Encodes ASCII with the Gen 4 charmap; '\n' newline, '\r' scroll, '\f' new page. */
    private fun encode(text: String): IntArray {
        val reverse = HgssData.charmap.entries.filter { it.value.length == 1 }.associate { it.value[0] to it.key }
        return text.map {
            when (it) {
                '\n' -> Gen4Text.NEWLINE
                '\r' -> Gen4Text.SCROLL
                '\u000C' -> Gen4Text.NEW_PAGE
                else -> reverse.getValue(it)
            }
        }.toIntArray() + Gen4Text.EOS
    }

    @Test
    fun visibleLinesFollowPagesAndScrolls() {
        val chars = encode("Hi!\nYou are awake.\u000CYour friend was here.\rShe played.\nWith MARILL.")
        assertEquals("Hi!\nYou are awake.", Gen4Text.visibleLines(chars, 18))
        // At the page break the old page is still shown, until the next page's first character prints.
        assertEquals("Hi!\nYou are awake.", Gen4Text.visibleLines(chars, 19))
        assertEquals("Y", Gen4Text.visibleLines(chars, 20))
        assertEquals("Your friend was here.\nShe played.", Gen4Text.visibleLines(chars, 52))
        assertEquals("She played.\nWith MARILL.", Gen4Text.visibleLines(chars, null))
    }

    @Test
    fun goalsFollowTheStory() {
        fun story(flags: Set<Int> = emptySet(), vararg vars: Pair<Int, Int>) = StoryInfo(flags, vars.toMap())
        fun goals(story: StoryInfo, mapId: Int) = HgssStoryTable.openGoals(story).map { it.describe(mapId) }
        assertEquals(listOf("Talk to Mom on the first floor of your house"), goals(story(), 63))
        assertEquals(
            listOf("Leave the house (exit mat at the bottom of the first floor)"),
            goals(story(setOf(HgssProgress.FLAG_GOT_BAG), HgssProgress.VAR_PLAYERS_HOUSE_1F to 1), 63),
        )
        val afterEgg = story(
            setOf(HgssProgress.FLAG_GOT_STARTER, HgssProgress.FLAG_GOT_POKEGEAR, HgssProgress.FLAG_GOT_POKEDEX, HgssProgress.FLAG_RIVAL_CHERRYGROVE),
            HgssProgress.VAR_PLAYERS_HOUSE_1F to 2, HgssProgress.VAR_ELMS_LAB to 3, HgssProgress.VAR_NEW_BARK_TOWN to 2,
            HgssProgress.VAR_NEW_BARK_WEST_EXIT to 1, HgssProgress.VAR_CHERRYGROVE to 4, HgssProgress.VAR_MR_POKEMONS_HOUSE to 1,
            HgssProgress.VAR_ROUTE_30 to 3,
        )
        assertEquals(listOf("Return to Prof. Elm's lab in New Bark Town with the Mystery Egg"), goals(afterEgg, 60))
        // With the Zephyr Badge, the steps before it count as done: the table goes on (Togepi Egg in Violet City).
        assertEquals(listOf(HgssStoryTable.step("johto:togepi_egg")!!.description), goals(afterEgg.copy(badges = setOf(HgssStoryTable.ZEPHYR)), 60))
    }
}
