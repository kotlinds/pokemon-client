package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.ZeroMemory
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.BlockerCause
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Route 11 Snorlax blocker (Poké Flute, then talk again) and the credits / S.S. Aqua cut scenes. */
class HgssGearBlockersTest {

    private fun person(id: Int, x: Int, z: Int, mapId: Int) = MapObjectInfo(
        id = id, sprite = "X", x = x, z = z, dx = 0, dz = 0, height = 0, facing = "south", movement = 0, type = 0,
        scriptId = 1, hidden = false, kind = "npc", mapId = mapId, eventFlag = 0,
    )

    private fun route11(story: StoryInfo = StoryInfo()) = HgssState(
        frame = 0, mode = GameMode.OVERWORLD, story = story,
        location = LocationInfo(ROUTE_11, 0, 0, 0, "south", false, "WALKING"),
        surroundings = Surroundings(objects = listOf(person(4, 10, 10, ROUTE_11))),
    )

    @Test
    fun theSnorlaxSaysToTuneThePokeFluteThenTalkToItAgain() {
        val blocker = HgssBlockers.of(route11()).single()
        assertEquals("person:4", blocker.target)
        assertTrue("tune_radio station:poke_flute" in blocker.reason)
        assertTrue("talk to the Snorlax again" in blocker.reason)
        assertEquals(BlockerCause.SleepingPokemon(143, RadioStation.POKE_FLUTE), blocker.cause)
        assertTrue(HgssBlockers.of(route11(StoryInfo(flags = setOf(HgssStoryTable.Flags.SNORLAX_BEATEN)))).isEmpty())
    }

    private val noRam = HgssMemory(ZeroMemory, HgssVersion.HEARTGOLD_US)

    /** The credits as they roll and "The End" are read from their app ([HgssGameClearFixtureTest]); unreadable, a transition. */
    @Test
    fun unreadableCreditsAreATransition() {
        val credits = HgssCutsceneScreens.decode(noRam, HgssState(frame = 0, mode = GameMode.APP, modeDetail = "credits"))
        assertEquals(Screen.Animation(AnimationKind.TRANSITION), credits)
        assertTrue("soft_reset" in HgssCutsceneScreens.CREDITS_HINT)
    }

    @Test
    fun theShipCrossingIsACutScene() {
        val ship = HgssCutsceneScreens.decode(noRam, HgssState(frame = 0, mode = GameMode.APP, modeDetail = "ship_crossing"))
        assertEquals(Screen.Animation(AnimationKind.CUTSCENE, HgssCutsceneScreens.SHIP_HINT), ship)
    }

    private companion object {
        const val ROUTE_11 = 19
    }
}
