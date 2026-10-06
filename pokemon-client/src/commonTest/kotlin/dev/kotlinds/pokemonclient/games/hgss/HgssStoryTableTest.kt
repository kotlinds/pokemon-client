package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.StoryCondition
import dev.kotlinds.pokemonclient.state.StoryCondition.FlagSet
import dev.kotlinds.pokemonclient.state.StoryCondition.HasBadge
import dev.kotlinds.pokemonclient.state.StoryCondition.Or
import dev.kotlinds.pokemonclient.state.StoryCondition.VarAtLeast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The story table's logic on synthetic flags / vars / badges (no RAM). */
class HgssStoryTableTest {

    private fun goal(story: StoryInfo) = HgssStoryTable.goal(story)?.id

    /** The smallest progress that makes [condition] hold: the first alternative of an [Or], every part of an [StoryCondition.And]. */
    private fun StoryInfo.witness(condition: StoryCondition): StoryInfo = when (condition) {
        is FlagSet -> copy(flags = flags + condition.id)
        is VarAtLeast -> copy(vars = vars + (condition.id to maxOf(variable(condition.id), condition.value)))
        is HasBadge -> copy(badges = badges + condition.index)
        is Or -> witness(condition.any.first())
        is StoryCondition.And -> condition.all.fold(this) { s, c -> s.witness(c) }
    }

    @Test
    fun idsAreStableUniqueAndJohtoComesBeforeKanto() {
        val ids = HgssStoryTable.steps.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids")
        assertTrue(ids.all { it.startsWith("johto:") || it.startsWith("kanto:") })
        assertTrue(ids.indexOfLast { it.startsWith("johto:") } < ids.indexOfFirst { it.startsWith("kanto:") })
        assertEquals("johto:talk_to_mom", ids.first())
        assertEquals("kanto:red", ids.last())
        // Every Johto and Kanto badge has its step.
        val badgeSteps = HgssStoryTable.steps.filter { it.id.contains(":badge_") }
        assertEquals(16, badgeSteps.size)
    }

    @Test
    fun aNewGameStartsWithMom() {
        assertEquals("johto:talk_to_mom", goal(StoryInfo()))
        assertEquals(
            "Go downstairs (stairs in the top-left corner) and talk to Mom",
            HgssStoryTable.steps.first().describe(64),
        )
        assertEquals("Talk to Mom on the first floor of your house", HgssStoryTable.steps.first().describe(null))
    }

    @Test
    fun playingEveryStepInOrderWalksThroughTheWholeTable() {
        // Doing each step (its own condition only) moves the goal exactly one step further: no step is done by an
        // earlier one, and the order of the table is the order of the story.
        var story = StoryInfo()
        for (step in HgssStoryTable.steps) {
            assertEquals(step.id, goal(story))
            story = story.witness(step.done)
        }
        assertNull(goal(story), "the story is over after Red")
    }

    @Test
    fun aCheckpointCountsTheStepsBeforeItAsDone() {
        // Only the Fog Badge: everything before it (even without its flags) is behind the player.
        assertEquals("johto:surf", goal(StoryInfo(badges = setOf(HgssStoryTable.FOG))))
        // Only the Hall of Fame: Kanto starts.
        assertEquals("kanto:ss_ticket", goal(StoryInfo(flags = setOf(HgssStoryTable.Flags.GAME_CLEAR))))
        // A checkpoint does not jump ahead: with the Zephyr Badge only, the next step is right after it.
        assertEquals("johto:togepi_egg", goal(StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR))))
    }

    @Test
    fun midgameBadgesInAnotherOrderKeepTheMissingStep() {
        // Glacier before Storm/Mineral (the midgame gyms can be done in any order): the Lighthouse is still to do.
        val story = StoryInfo(badges = (0..3).toSet() + HgssStoryTable.GLACIER, flags = setOf(HgssStoryTable.Flags.GOT_HM03))
        assertEquals("johto:lighthouse", goal(story))
    }

    @Test
    fun eightBadgesAndTheKimonoGirlsLeftPointToTheDanceTheater() {
        // The state of the agent's real run (see HgssStoryFixtureTest): 8 badges, Elm visited, Kimono Girl met.
        val story = StoryInfo(
            badges = (0..7).toSet(),
            vars = mapOf(HgssStoryTable.Vars.NEW_BARK_EAST_EXIT to 2, HgssStoryTable.Vars.DANCE_THEATER to 5),
        )
        assertEquals("johto:kimono_girls", goal(story))
        val afterBell = story.copy(flags = setOf(HgssStoryTable.Flags.BEAT_KIMONO_GIRLS))
        assertEquals("johto:bell_tower", goal(afterBell))
    }

    @Test
    fun kantoGymsInAnyOrderPointToTheFirstMissingOne() {
        val story = StoryInfo(
            badges = (0..7).toSet() + HgssStoryTable.MARSH,
            flags = setOf(HgssStoryTable.Flags.GAME_CLEAR, HgssStoryTable.Flags.ARRIVED_IN_VERMILION),
        )
        assertEquals("kanto:badge_thunder", goal(story))
    }

    private fun open(story: StoryInfo) = HgssStoryTable.openGoals(story).map { it.id }

    @Test
    fun everyAfterNamesAnEarlierStep() {
        val ids = HgssStoryTable.steps.map { it.id }
        HgssStoryTable.steps.forEachIndexed { i, step ->
            step.after?.forEach { id -> assertTrue(id in ids.take(i), "${step.id} comes after $id, which must be an earlier step") }
        }
    }

    @Test
    fun inOrderOnlyOneGoalIsOpen() {
        assertEquals(listOf("johto:talk_to_mom"), open(StoryInfo()))
        assertEquals(listOf("johto:togepi_egg"), open(StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR))))
    }

    @Test
    fun fromTheFogBadgeSurfAndTheLighthouseAreOpenButThePharmacyWaitsForSurf() {
        // Olivine is reached on foot and the Lighthouse checks no badge (scr_seq_0066_D27R0107.s): Jasmine's errand
        // opens with the Fog Badge, beside the Surf errand at the Dance Theater.
        val fog = StoryInfo(badges = (0..3).toSet())
        assertEquals(listOf("johto:surf", "johto:lighthouse"), open(fog))
        // Jasmine talked to, still no Surf: the Cianwood pharmacy is across the sea, not open yet.
        val talked = fog.copy(flags = setOf(HgssStoryTable.Flags.TALKED_TO_JASMINE_LIGHTHOUSE))
        assertEquals(listOf("johto:surf"), open(talked))
        assertEquals(listOf("johto:badge_storm", "johto:secretpotion", "johto:red_gyarados"), open(talked.copy(flags = talked.flags + HgssStoryTable.Flags.GOT_HM03)))
    }

    @Test
    fun theAzaleaRivalCanBeMetBeforeBugsy() {
        // The rival's trigger is armed once the Slowpoke Well is cleared (scr_seq_0060_D26R0102.s:89).
        val well = StoryInfo(
            badges = setOf(HgssStoryTable.ZEPHYR),
            flags = setOf(HgssStoryTable.Flags.AZALEA_ROCKETS_BEATEN),
            vars = mapOf(HgssStoryTable.Vars.ROUTE_32_GATE to 1),
        )
        assertEquals(listOf("johto:badge_hive", "johto:rival_azalea"), open(well))
        assertEquals("johto:badge_hive", goal(well))
    }

    @Test
    fun whitneysGymWaitsForTheRadioCardQuiz() {
        // Cut in hand, no quiz: the Radio Tower comes before the Gym (the woman on its door leaves with FLAG_UNK_318).
        val cut = StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR, HgssStoryTable.HIVE), flags = setOf(HgssStoryTable.Flags.GOT_HM01))
        assertEquals("johto:radio_card", goal(cut))
        val description = HgssStoryTable.step("johto:radio_card")!!.description
        assertTrue("Radio Tower" in description)
        // Accept the quiz (its own yes / no, scr_seq_0029_D23R0101.s:112), then the five answers checked by the
        // script (0 = YES): named by the menu's entry ids, never by displayed text.
        assertEquals(listOf(true, true, false, true, false), HgssStoryTable.RADIO_QUIZ_ANSWERS)
        assertTrue(
            "answer option:yes; then answer the five questions in order: option:yes, option:yes, option:no, option:yes, option:no" in description,
            description,
        )
        assertEquals("johto:badge_plain", goal(cut.copy(flags = cut.flags + HgssStoryTable.Flags.WON_RADIO_CARD_QUIZ)))
        // The Plain Badge counts the quiz as done (a save without the flag never asks for it again).
        assertEquals("johto:sudowoodo", goal(StoryInfo(badges = setOf(HgssStoryTable.PLAIN))))
    }

    @Test
    fun theIcePathNeedsStrengthAndCrossingItWithoutHm07DoesNotHoldBlackthornBack() {
        val description = HgssStoryTable.step("johto:ice_path")!!.description
        assertTrue("Strength" in description && "Route 42" in description, description)
        // The Radio Tower freed, the Ice Path crossed (Blackthorn's fly point) without picking up HM07: Clair is next.
        val crossed = StoryInfo(
            badges = (0..6).toSet(),
            flags = setOf(HgssStoryTable.Flags.BEAT_RADIO_TOWER_ROCKETS, HgssStoryTable.Flags.REACHED_BLACKTHORN),
        )
        assertEquals("johto:clair", goal(crossed))
        // The other way round: HM07 picked up (it lies in the part entered from Route 44) but Blackthorn not reached
        // yet, the Ice Path is still the goal.
        val hm07Only = crossed.copy(flags = setOf(HgssStoryTable.Flags.BEAT_RADIO_TOWER_ROCKETS, HgssStoryTable.Flags.GOT_HM07))
        assertEquals("johto:ice_path", goal(hm07Only))
        // HM07 is asked for again only on the way to the League, right after Ho-Oh.
        val hoOh = StoryInfo(badges = (0..7).toSet(), flags = setOf(HgssStoryTable.Flags.HO_OH_DONE))
        assertEquals("johto:hm07", goal(hoOh))
        // Its fly suggestion is Mahogany Town, next to the Route 44 entrance (not Blackthorn City, across the whole Ice Path).
        assertEquals(87, HgssStoryTable.place("johto:hm07"))
        assertEquals("johto:league_gate", goal(hoOh.copy(flags = hoOh.flags + HgssStoryTable.Flags.GOT_HM07)))
        // Reaching the Reception Gate (Tohjo Falls climbed) counts it as done.
        assertEquals("johto:victory_road", goal(hoOh.copy(flags = hoOh.flags + HgssStoryTable.Flags.REACHED_LEAGUE_GATE)))
    }

    @Test
    fun theFlyPointFlagsAreTheDecompsOnes() {
        // FLAG_SYS_FLYPOINT_BLACKTHORN / _VICTORY_ROAD / _INDIGO (include/constants/flags.h), built from the fly map's first flag.
        assertEquals(0x9C5, HgssStoryTable.Flags.REACHED_BLACKTHORN)
        assertEquals(0x9D1, HgssStoryTable.Flags.REACHED_LEAGUE_GATE)
        assertEquals(0x9B9, HgssStoryTable.Flags.REACHED_INDIGO_PLATEAU)
    }

    @Test
    fun theFieldMovesTheWayNeedsAreNamedWhereTheyAreNeeded() {
        // Checked on the ROM's maps (HgssWorldRoutingTest): Whirlpool in the Dragon's Den, Strength on Victory Road,
        // Rock Climb (and not Rock Smash) up Mt. Silver; Oak gives HM08 with the permission (scr_seq_0740_T01R0301.s:201).
        fun text(id: String) = HgssStoryTable.step(id)!!.description
        assertTrue("Whirlpool" in text("johto:badge_rising"))
        assertTrue("Strength" in text("johto:victory_road"))
        assertTrue("Rock Climb" in text("kanto:mt_silver_permission"))
        assertTrue("Rock Climb" in text("kanto:red") && "Rock Smash" !in text("kanto:red"))
    }

    @Test
    fun theEcruteakSceneVarIsNotReadItGoesUpAndDown() {
        // VAR_UNK_4079 goes 2, 1, 0, 3, 4 along the story (D18R0102, T27GYM0101, T27, T20R0101): never compared with >=.
        assertFalse(0x4079 in HgssStoryTable.varIds)
        assertEquals("johto:burned_tower", goal(StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR, HgssStoryTable.HIVE, HgssStoryTable.PLAIN), flags = setOf(HgssStoryTable.Flags.SUDOWOODO_GONE), vars = mapOf(0x4079 to 3))))
    }

    @Test
    fun afterSurfJasmineChuckAndTheLakeOfRageAreOpen() {
        val story = StoryInfo(badges = (0..3).toSet(), flags = setOf(HgssStoryTable.Flags.GOT_HM03))
        assertEquals(listOf("johto:lighthouse", "johto:badge_storm", "johto:red_gyarados"), open(story))
        // Pryce first: his side is done, the two others are still open; the Radio Tower waits for them.
        val pryce = story.copy(badges = story.badges + HgssStoryTable.GLACIER)
        assertEquals(listOf("johto:lighthouse", "johto:badge_storm"), open(pryce))
        val all = pryce.copy(badges = pryce.badges + HgssStoryTable.STORM + HgssStoryTable.MINERAL, flags = pryce.flags + HgssStoryTable.Flags.GOT_HM02)
        assertEquals(listOf("johto:radio_tower"), open(all))
    }

    @Test
    fun fromVermilionEveryKantoGymButBlainesAndThePowerPlantAreOpen() {
        val story = StoryInfo(badges = (0..7).toSet(), flags = setOf(HgssStoryTable.Flags.GAME_CLEAR, HgssStoryTable.Flags.ARRIVED_IN_VERMILION))
        // Blaine waits for the way west: Route 19 is closed by workmen until he is beaten (scr_seq_0015_D11R0106.s:95).
        assertEquals(
            listOf("kanto:badge_thunder", "kanto:badge_marsh", "kanto:badge_rainbow", "kanto:badge_soul", "kanto:power_plant"),
            open(story),
        )
        // The Cerulean Gym scene opens two lines: the grunt on Route 24 (→ power → the way west) and Misty on Route 25.
        val gym = story.copy(flags = story.flags + HgssStoryTable.Flags.POWER_PLANT_STORY, vars = mapOf(HgssStoryTable.Vars.ROUTE_24_ROCKET to 1))
        assertEquals(listOf("kanto:route_24_rocket", "kanto:misty"), open(gym).filterNot { it.contains("badge_") })
    }

    @Test
    fun blainesGymOpensWithViridianCityAndIsReachedFromPalletTown() {
        val west = StoryInfo(
            badges = (0..7).toSet(),
            flags = setOf(HgssStoryTable.Flags.GAME_CLEAR, HgssStoryTable.Flags.ARRIVED_IN_VERMILION, HgssStoryTable.Flags.RESTORED_POWER, HgssStoryTable.Flags.UNLOCKED_WEST_KANTO),
            vars = mapOf(HgssStoryTable.Vars.MISTY to 2),
        )
        assertTrue("kanto:badge_volcano" in open(west))
        val volcano = HgssStoryTable.step("kanto:badge_volcano")!!.description
        assertTrue("Pallet Town" in volcano && "Route 21" in volcano, volcano)
        // The same save before Viridian City: Blaine is not open.
        assertFalse("kanto:badge_volcano" in open(west.copy(flags = west.flags - HgssStoryTable.Flags.UNLOCKED_WEST_KANTO)))
    }

    @Test
    fun theKantoGymsLeftAreAllOpenNotOnlyTheFirstOne() {
        // The user's save (story_kanto_cinnabar): Thunder, Rainbow, Soul, Marsh, Volcano; Misty back; west Kanto open.
        val story = StoryInfo(
            badges = (0..7).toSet() + setOf(HgssStoryTable.THUNDER, HgssStoryTable.RAINBOW, HgssStoryTable.SOUL, HgssStoryTable.MARSH, HgssStoryTable.VOLCANO),
            flags = setOf(HgssStoryTable.Flags.GAME_CLEAR, HgssStoryTable.Flags.ARRIVED_IN_VERMILION, HgssStoryTable.Flags.RESTORED_POWER, HgssStoryTable.Flags.UNLOCKED_WEST_KANTO),
            vars = mapOf(HgssStoryTable.Vars.MISTY to 2),
        )
        assertEquals(listOf("kanto:badge_cascade", "kanto:badge_boulder"), open(story))
        assertEquals("kanto:badge_cascade", goal(story))
        // With the seven badges, Blue is next, alone.
        val seven = story.copy(badges = story.badges + HgssStoryTable.CASCADE + HgssStoryTable.BOULDER)
        assertEquals(listOf("kanto:blue_cinnabar"), open(seven))
    }

    @Test
    fun severalGoalsAreSeparateEntries() {
        val story = StoryInfo(badges = (0..3).toSet(), flags = setOf(HgssStoryTable.Flags.GOT_HM03))
        assertEquals(3, HgssStoryTable.openGoals(story).size)
        // One goal: a list of one.
        assertEquals(listOf(HgssStoryTable.step("johto:togepi_egg")!!.description), HgssStoryTable.openGoals(StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR))).map { it.description })
    }

    @Test
    fun conditionsAreTypedAndListTheirIds() {
        val c = Or(FlagSet(1), StoryCondition.And(VarAtLeast(0x4100, 2), HasBadge(3)))
        assertEquals(setOf(1), c.flagIds())
        assertEquals(setOf(0x4100), c.varIds())
        assertTrue(c.holds(StoryInfo(flags = setOf(1))))
        assertFalse(c.holds(StoryInfo(vars = mapOf(0x4100 to 2))))
        assertTrue(c.holds(StoryInfo(vars = mapOf(0x4100 to 3), badges = setOf(3))))
    }

    @Test
    fun theReaderReadsEveryIdTheTableNeeds() {
        val flags = HgssStoryTable.steps.flatMap { it.done.flagIds() } + HgssBlockers.curated.flatMap { it.liftedWhen?.flagIds().orEmpty() }
        val vars = HgssStoryTable.steps.flatMap { it.done.varIds() } + HgssBlockers.curated.flatMap { it.liftedWhen?.varIds().orEmpty() }
        assertTrue(HgssStoryTable.flagIds.containsAll(flags))
        assertTrue(HgssStoryTable.varIds.containsAll(vars))
        assertTrue(vars.all { it in 0x4000 until 0x4000 + 0x170 }, "only save vars")
        assertTrue(flags.all { it in 1 until 0xB00 }, "only save flags")
    }

    @Test
    fun completedStepsAreTheDoneOnesInOrder() {
        assertEquals(emptyList(), HgssStoryTable.completed(StoryInfo()).map { it.id })
        // Mom's talk done: the first step only.
        assertEquals(listOf("johto:talk_to_mom"), HgssStoryTable.completed(StoryInfo(flags = setOf(HgssProgress.FLAG_GOT_BAG))).map { it.id })
        // A checkpoint (the Zephyr Badge) counts every step before it as done.
        val ids = HgssStoryTable.steps.map { it.id }
        assertEquals(ids.take(ids.indexOf("johto:badge_zephyr") + 1), HgssStoryTable.completed(StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR))).map { it.id })
    }
}
