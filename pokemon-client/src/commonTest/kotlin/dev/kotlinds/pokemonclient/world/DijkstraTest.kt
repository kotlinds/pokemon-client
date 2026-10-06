package dev.kotlinds.pokemonclient.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The routes' one [dijkstra], on a line of numbered places (each move to the next one costs 1, back 3). */
class DijkstraTest {

    private fun line(state: Int): List<SearchMove<Int, String>> =
        listOf(SearchMove(state + 1, 1, "forward"), SearchMove(state - 1, 3, "back")).filter { it.to in 0..9 }

    @Test
    fun theCheapestPathIsReadBackInOrder() {
        val path = assertIs<SearchResult.Found<Int, String>>(dijkstra(start = 2, place = { it }, isGoal = { it == 0 }) { s, _ -> line(s) }).path
        assertEquals(listOf(1, 0), path.states)
        assertEquals(listOf("back", "back"), path.labels)
        assertEquals(6, path.cost)
    }

    @Test
    fun theStartIsNeverAGoal() {
        // Every place is a goal: the first one after the start wins, never the start itself.
        val path = assertIs<SearchResult.Found<Int, String>>(dijkstra(start = 0, place = { it }, isGoal = { true }) { s, _ -> line(s) }).path
        assertEquals(listOf(1), path.states)
    }

    @Test
    fun withoutAGoalItGivesEveryPlaceWithinTheCost() {
        val costs = assertIs<SearchResult.Exhausted<Int>>(dijkstra<Int, Int, String>(start = 0, place = { it }, maxCost = 3) { s, _ -> line(s) }).costs
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3), costs)
    }

    @Test
    fun theBoundCountsPlacesNotHeadings() {
        // States are (place, heading): two headings per place still count as one place for the bound.
        val moves = { s: Pair<Int, Boolean>, _: Int -> listOf(SearchMove(s.first + 1 to true, 1, ""), SearchMove(s.first + 1 to false, 1, "")) }
        assertIs<SearchResult.Found<Pair<Int, Boolean>, String>>(dijkstra(start = 0 to true, place = { it.first }, isGoal = { it.first == 5 }, maxPlaces = 5, moves = moves))
        assertEquals(SearchResult.OverBound, dijkstra(start = 0 to true, place = { it.first }, isGoal = { it.first == 5 }, maxPlaces = 4, moves = moves))
    }

    /**
     * A turn costs [turnCost] of the state's place on top of the move: across an open 3x3 grid, the cheapest way to
     * the far corner turns once (4 moves + 1 turn), never zigzags; with no turn cost, every place is explored once.
     */
    @Test
    fun aTurnCostsTheTurnCostOfThePlaceItIsMadeOn() {
        data class S(val x: Int, val y: Int, val dir: dev.kotlinds.pokemonclient.Direction?)
        val moves = { s: S, turnCost: Int ->
            dev.kotlinds.pokemonclient.Direction.entries.mapNotNull { d ->
                val n = S(s.x + d.dx, s.y + d.dy, d)
                if (n.x !in 0..2 || n.y !in 0..2) null
                else SearchMove(n, 1 + if (s.dir != null && s.dir != d) turnCost else 0, d)
            }
        }
        val path = assertIs<SearchResult.Found<S, dev.kotlinds.pokemonclient.Direction>>(
            dijkstra(start = S(0, 0, null), place = { it.x to it.y }, isGoal = { it.x == 2 && it.y == 2 }, turnCost = { 5 }, moves = moves),
        ).path
        assertEquals(4 + 5, path.cost)
        assertEquals(1, path.labels.zipWithNext().count { (a, b) -> a != b })
        val plain = assertIs<SearchResult.Found<S, dev.kotlinds.pokemonclient.Direction>>(
            dijkstra(start = S(0, 0, null), place = { it.x to it.y }, isGoal = { it.x == 2 && it.y == 2 }, moves = moves),
        ).path
        assertEquals(4, plain.cost)
    }
}
