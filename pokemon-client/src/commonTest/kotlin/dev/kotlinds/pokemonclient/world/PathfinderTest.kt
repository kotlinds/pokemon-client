package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Areas drawn in ASCII: '.' floor, '#' wall, '"' tall grass, 'v' '^' '<' '>' ledges (jump in that direction),
 * '~' surfable water, 'W' warp (taken on entering it), 'M' an exit mat taken by pressing south on it, 'X' a warp nothing
 * takes (an arrival point), 'H' a second surface 40 units higher (a walkway), '/' stairs (height 20),
 * '*' ice, 'R' 'L' 'U' 'D' spinner arrows (push right, left, up, down), 'S' the spinner stop tile, '@' whirlpool,
 * '|' waterfall, 'C' a Rock Climb wall climbed east-west, 'N' one climbed north-south (height 20), 'B' a floor with
 * two surfaces (0 and 40: a tile under a walkway).
 */
private fun area(vararg rows: String): Area {
    val height = rows.size
    val width = rows.maxOf { it.length }
    val warps = mutableListOf<Warp>()
    val tiles = Array<TileInfo?>(width * height) { i ->
        val c = rows[i / width].getOrElse(i % width) { ' ' }
        val x = i % width
        val y = i / width
        when (c) {
            '.' -> TileInfo(false, TileKind.Floor, listOf(0))
            '#' -> TileInfo(true, TileKind.Wall)
            '"' -> TileInfo(false, TileKind.TallGrass, listOf(0))
            'v' -> TileInfo(false, TileKind.Ledge(Direction.SOUTH))
            '^' -> TileInfo(false, TileKind.Ledge(Direction.NORTH))
            '<' -> TileInfo(false, TileKind.Ledge(Direction.WEST))
            '>' -> TileInfo(false, TileKind.Ledge(Direction.EAST))
            '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
            'W' -> TileInfo(false, TileKind.Door, listOf(0)).also { warps += Warp(0, warps.size, x, y, 1, 0) }
            'M' -> TileInfo(false, TileKind.Door, listOf(0)).also { warps += Warp(0, warps.size, x, y, 1, 0, WarpTrigger.Press(Direction.SOUTH)) }
            'X' -> TileInfo(false, TileKind.Floor, listOf(0)).also { warps += Warp(0, warps.size, x, y, 1, 0, WarpTrigger.Never) }
            'H' -> TileInfo(false, TileKind.Floor, listOf(40))
            '/' -> TileInfo(false, TileKind.Floor, listOf(20))
            '*' -> TileInfo(false, TileKind.Ice, listOf(0))
            'R' -> TileInfo(false, TileKind.Spinner(Direction.EAST), listOf(0))
            'L' -> TileInfo(false, TileKind.Spinner(Direction.WEST), listOf(0))
            'U' -> TileInfo(false, TileKind.Spinner(Direction.NORTH), listOf(0))
            'D' -> TileInfo(false, TileKind.Spinner(Direction.SOUTH), listOf(0))
            'S' -> TileInfo(false, TileKind.SpinnerStop, listOf(0))
            '@' -> TileInfo(false, TileKind.Whirlpool)
            '|' -> TileInfo(false, TileKind.Waterfall)
            'C' -> TileInfo(true, TileKind.RockClimb(ClimbAxis.EAST_WEST))
            'N' -> TileInfo(true, TileKind.RockClimb(ClimbAxis.NORTH_SOUTH), listOf(20))
            'B' -> TileInfo(false, TileKind.Floor, listOf(0, 40))
            else -> null
        }
    }
    return Area(0, "test", 0, 0, width, height, tiles, warps = warps)
}

class PathfinderTest {

    private fun Pathfinder.to(x: Int, y: Int, from: Node, options: RouteOptions = RouteOptions(), goalTiles: Set<Pair<Int, Int>> = emptySet()) =
        route(from, options, goalTiles) { it.x == x && it.y == y }

    @Test
    fun findsTheShortestWalkAroundWalls() {
        val map = area(
            ".....",
            ".###.",
            ".....",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 0, Node(0, 0)))
        assertEquals(4, found.route.edges.size)
    }

    @Test
    fun theFollowingPokemonIsNotAnObstacleButPeopleAre() {
        val map = area("...")
        val follower = Pathfinder(map, Overlay(listOf(LiveObject(1, 0, Direction.EAST, isFollower = true))))
        assertIs<Pathfinder.Result.Found>(follower.to(2, 0, Node(0, 0)))
        val person = Pathfinder(map, Overlay(listOf(LiveObject(1, 0, Direction.EAST))))
        assertIs<Pathfinder.Result.Failed>(person.to(2, 0, Node(0, 0)))
    }

    @Test
    fun ledgesAreJumpedOnlyInTheirDirectionAndOnlyWhenAccepted() {
        val map = area(
            "...",
            "vvv",
            "...",
        )
        val pathfinder = Pathfinder(map)
        // Down: only by jumping, refused unless one-way routes are accepted.
        assertEquals(RouteFailure.OnlyOneWay, assertIs<Pathfinder.Result.Failed>(pathfinder.to(1, 2, Node(1, 0))).failure)
        val jump = assertIs<Pathfinder.Result.Found>(pathfinder.to(1, 2, Node(1, 0), RouteOptions(acceptOneWay = true)))
        assertIs<Edge.Jump>(jump.route.edges.single())
        assertTrue(RouteWarning.OneWay in jump.route.warnings)
        // Up: never.
        assertIs<Pathfinder.Result.Failed>(pathfinder.to(1, 0, Node(1, 2), RouteOptions(acceptOneWay = true)))
    }

    @Test
    fun aLedgeWithAWayBackIsJustAShortcut() {
        // The way back goes up the right column: jumping is not one way, so it's taken without asking.
        val map = area(
            "....",
            "vvv.",
            "....",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0)))
        assertTrue(found.route.edges.any { it is Edge.Jump })
        assertTrue(RouteWarning.OneWay !in found.route.warnings)
    }

    @Test
    fun withoutAWayBackTheRouteAvoidingLedgesIsPreferred() {
        // The ledges cut the map in two except through the right column... which also has a ledge: no way back up.
        val map = area(
            "....",
            "vvvv",
            "....",
        )
        assertEquals(RouteFailure.OnlyOneWay, assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(0, 2, Node(0, 0))).failure)
    }

    @Test
    fun tallGrassIsAvoidedOnlyWhenAsked() {
        val map = area(
            "...",
            "\"#.",
            "...",
        )
        val direct = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0)))
        assertEquals(2, direct.route.edges.size)
        val around = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0), RouteOptions(avoidTallGrass = true)))
        assertEquals(6, around.route.edges.size)
        assertTrue(around.route.warnings.none { it is RouteWarning.CrossesTallGrass })
    }

    @Test
    fun waterNeedsSurf() {
        val map = area(".~.")
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(2, 0, Node(0, 0)))
        assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(0, 0), RouteOptions(canSurf = true)))
        assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(1, 0), RouteOptions(mode = MovementMode.SURF)))
    }

    @Test
    fun warpsAreOnlyEnteredWhenTheyAreTheDestination() {
        val map = area(
            ".W.",
            "...",
        )
        val around = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(0, 0)))
        assertTrue(around.route.edges.none { it.to.x == 1 && it.to.y == 0 })
        val into = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(1, 0, Node(0, 0), goalTiles = setOf(1 to 0)))
        assertEquals(1, into.route.edges.size)
    }

    /**
     * The doormat of the shuffled warps (NOTES-run-map-randomizer: arrived on the tile below the Celadon Dept Store's
     * exit mat, "no way… not connected" although `step north 2` led back in): an exit mat taken by pressing south is
     * crossed going north like floor, and never left towards south (that press takes it).
     */
    @Test
    fun anExitMatIsCrossedAnyWayButItsOwn() {
        val map = area(
            "...",
            "#M#",
            "#.#",
        )
        val inside = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 0, Node(1, 2)))
        assertEquals(1 to 1, inside.route.edges.first().to.let { it.x to it.y })
        // From the room, the tile below is only reached through the mat going south: that press takes the warp.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(1, 2, Node(0, 0)))
        // Standing on the mat (walls east and west): north is a step, south isn't.
        val moves = Pathfinder(map).neighbours(Node(1, 1), RouteOptions()).map { it.direction }
        assertEquals(listOf(Direction.NORTH), moves)
    }

    /** A warp nothing takes (only an arrival point) is walked like floor. */
    @Test
    fun aWarpNothingTakesIsFloor() {
        val map = area("#X#", ".X.", "#.#")
        val across = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 1, Node(0, 1)))
        assertEquals(2, across.route.edges.size)
    }

    @Test
    fun heightLevelsAreOnlyJoinedThroughStairs() {
        // Ground row, a walkway (H) 40 units up next to it; stairs (/) at 20 connect them on the right.
        val map = area(
            "HHH",
            "../",
        )
        val pathfinder = Pathfinder(map)
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.to(0, 0, Node(0, 1)))
        assertEquals(listOf(1 to 1, 2 to 1, 2 to 0, 1 to 0, 0 to 0), found.route.edges.map { it.to.x to it.to.y })
    }

    @Test
    fun refusedMovesAreRememberedAndAvoided() {
        val map = area(
            "..",
            "..",
        )
        val refused = Pathfinder(map, Overlay(refused = setOf(Node(0, 0) to Direction.EAST)))
        val found = assertIs<Pathfinder.Result.Found>(refused.to(1, 0, Node(0, 0)))
        assertEquals(3, found.route.edges.size)
    }

    @Test
    fun trainerSightIsAvoidedWhenAsked() {
        val map = area(
            "....",
            "....",
        )
        // A trainer at (3,0) looking west sees (2,0), (1,0), (0,0)... with range 2: (2,0) and (1,0).
        val overlay = Overlay(listOf(LiveObject(3, 0, Direction.WEST, sightRange = 2)))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map, overlay).to(2, 1, Node(0, 0), RouteOptions(avoidTrainers = true)))
        assertTrue(found.route.edges.none { it.to.y == 0 })
    }

    @Test
    fun levelAtPicksTheClosestSurface() {
        val map = Area(0, "bridge", 0, 0, 1, 1, arrayOf(TileInfo(false, TileKind.Floor, listOf(0, 64))))
        assertEquals(1, Pathfinder(map).levelAt(0, 0, 60))
        assertEquals(0, Pathfinder(map).levelAt(0, 0, 5))
    }

    @Test
    fun reachableStopsAtWallsAndLedgesAndHonoursTheRadius() {
        val map = area(
            "..#..",
            "vv#..",
            "..#..",
        )
        val reach = Pathfinder(map).reachable(Node(0, 0)).keys.map { it.x to it.y }.toSet()
        assertEquals(setOf(0 to 0, 1 to 0), reach)
        val jumping = Pathfinder(map).reachable(Node(0, 0), RouteOptions(acceptOneWay = true)).keys.map { it.x to it.y }.toSet()
        assertTrue(0 to 2 in jumping && 1 to 2 in jumping)
        assertEquals(setOf(3 to 0, 4 to 0), Pathfinder(map).reachable(Node(3, 0), maxCost = 1).keys.map { it.x to it.y }.toSet() - setOf(3 to 1))
    }

    // region Ice and spinners

    private fun Edge.tilePositions() = tiles.map { it.x to it.y }

    @Test
    fun iceSlidesToTheFirstTileThatIsNotIce() {
        val map = area(
            "#####.",
            ".****..",
            "#####.",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(5, 1, Node(0, 1)))
        val slide = assertIs<Edge.Slide>(found.route.edges.single())
        assertEquals(Direction.EAST, slide.direction)
        assertEquals(Node(5, 1), slide.to)
        assertEquals(listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1), slide.tilePositions())
        // The player can't stop in the middle of the ice.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 1, Node(0, 1)))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 1, Node(6, 1)))
    }

    @Test
    fun iceStopsBeforeAWallOrAPerson() {
        val map = area("..***#")
        val wall = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 0, Node(0, 0)))
        assertEquals(Node(4, 0), assertIs<Edge.Slide>(wall.route.edges.last()).to)
        val person = Pathfinder(map, Overlay(listOf(LiveObject(4, 0, Direction.WEST))))
        val stopped = assertIs<Pathfinder.Result.Found>(person.to(3, 0, Node(0, 0)))
        assertEquals(listOf(2 to 0, 3 to 0), stopped.route.edges.last().tilePositions())
    }

    @Test
    fun iceRoutesUseRocksToStop() {
        // The goal (3,3) is in the middle of the ice: only reachable by sliding south from (3,0)... stopping on the
        // rock at (3,4). From the start, sliding east along row 1 goes to the wall: the route goes around.
        val map = area(
            "......",
            "#****#",
            "#****#",
            "#****#",
            "###.##",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 3, Node(0, 0)))
        val last = assertIs<Edge.Slide>(found.route.edges.last())
        assertEquals(Direction.SOUTH, last.direction)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3), last.tilePositions())
        // Down the column with the exit, the slide goes through to the floor below.
        val through = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(3, 4, Node(0, 0)))
        assertEquals(Node(3, 4), assertIs<Edge.Slide>(through.route.edges.last()).to)
    }

    @Test
    fun aSlideIntoAWarpIsOnlyPlannedWhenTheWarpIsTheDestination() {
        val map = area(".***W")
        // Sliding east would end in the warp: not a way to (3,0)...
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 0, Node(0, 0)))
        // ...but the way to the warp itself.
        val into = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 0, Node(0, 0), goalTiles = setOf(4 to 0)))
        assertEquals(Node(4, 0), assertIs<Edge.Slide>(into.route.edges.single()).to)
    }

    @Test
    fun spinnersPushUntilTheStopTileTurningOnArrows() {
        val map = area(
            "....",
            "R..D",
            "...S",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(3, 2, Node(0, 0)))
        val slide = assertIs<Edge.Slide>(found.route.edges.single())
        assertEquals(Direction.SOUTH, slide.direction)
        assertEquals(listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1, 3 to 2), slide.tilePositions())
    }

    @Test
    fun spinnersStopBeforeObstaclesAndNeverLoop() {
        val blocked = area(".R..#")
        val push = assertIs<Edge.Slide>(Pathfinder(blocked).neighbours(Node(0, 0), RouteOptions()).single())
        assertEquals(Node(3, 0), push.to)
        // R pushes east, L pushes back west: a loop forever is never planned.
        val loop = area(
            ".##",
            "R.L",
        )
        assertTrue(Pathfinder(loop).neighbours(Node(0, 0), RouteOptions()).isEmpty())
    }

    // endregion

    // region Field moves

    private fun Pathfinder.Result.needs(): RouteFailure.NeedsFieldMove =
        assertIs<RouteFailure.NeedsFieldMove>(assertIs<Pathfinder.Result.Failed>(this).failure)

    @Test
    fun aFailedRouteNamesTheFieldMoveThatOpensTheWay() {
        // The failure tells the land tile to use it from, and the direction to face (not only the water tile).
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.SURF, 2, 0, Node(1, 0), Direction.EAST), Pathfinder(area("..~~..")).to(5, 0, Node(0, 0)).needs())
        assertEquals(FieldMoveKind.WHIRLPOOL, Pathfinder(area("~@~")).to(2, 0, Node(0, 0), RouteOptions(mode = MovementMode.SURF)).needs().move)
        assertEquals(FieldMoveKind.WATERFALL, Pathfinder(area("~|~")).to(2, 0, Node(0, 0), RouteOptions(mode = MovementMode.SURF)).needs().move)
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.ROCK_CLIMB, 1, 1, Node(0, 1), Direction.EAST), Pathfinder(area("##", ".C.")).to(2, 1, Node(0, 1)).needs())
        // Nothing opens a wall.
        assertEquals(RouteFailure.Unreachable, assertIs<Pathfinder.Result.Failed>(Pathfinder(area(".#.")).to(2, 0, Node(0, 0))).failure)
    }

    @Test
    fun aBoulderBlocksTheRouteAndTheFailureSaysStrength() {
        val map = area(
            "#######",
            ".......",
            "#######",
        )
        val boulder = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.STRENGTH, 2, 1, Node(1, 1), Direction.EAST), Pathfinder(map, boulder).to(4, 1, Node(0, 1)).needs())
        // A corridor whose end the boulder can only be pushed into: no push frees 4,1, so Strength is no way there
        // (the dead-end boulder of Victory Road 2F), and the diagnosis says which boulder it left out.
        val short = area(
            "#####",
            ".....",
            "#####",
        )
        val deadEnd = assertIs<Pathfinder.Result.Failed>(Pathfinder(short, boulder).to(4, 1, Node(0, 1)))
        assertEquals(RouteFailure.Unreachable, deadEnd.failure)
        assertEquals(listOf(2 to 1), deadEnd.blockers.stuckBoulders)
        // The fewest field moves: a way around a boulder is taken, and water is preferred to two obstacles.
        val open = area(
            ".....",
            ".....",
        )
        assertIs<Pathfinder.Result.Found>(Pathfinder(open, boulder).to(4, 1, Node(0, 1)))
        // A person is not an obstacle a field move clears: the failure names where they stand.
        val person = Overlay(listOf(LiveObject(2, 1, Direction.WEST)))
        assertEquals(RouteFailure.BlockedByPerson(2, 1), assertIs<Pathfinder.Result.Failed>(Pathfinder(map, person).to(4, 1, Node(0, 1))).failure)
    }

    // endregion

    // region Field moves used by routes

    @Test
    fun withSurfUsableTheRouteGoesFromTheShoreOntoTheWaterAndBack() {
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.SURF))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(area("..~~..")).to(5, 0, Node(0, 0), options))
        val edges = found.route.edges
        // One Surf (from the shore 1,0 facing east onto 2,0), then plain steps: landing is automatic.
        val surf = assertIs<FieldMoveEdge>(edges[1])
        assertEquals(FieldMoveKind.SURF, surf.move)
        assertEquals(Node(2, 0), surf.to)
        assertEquals(Direction.EAST, surf.direction)
        assertEquals(1, edges.count { it is FieldMoveEdge })
        assertEquals(Node(5, 0), found.route.end)
    }

    @Test
    fun alreadySurfingWaterIsPlainSteps() {
        val options = RouteOptions(mode = MovementMode.SURF, fieldMoves = setOf(FieldMoveKind.SURF))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(area("~~~~")).to(3, 0, Node(0, 0), options))
        assertTrue(found.route.edges.all { it is Edge.Step })
    }

    @Test
    fun waterfallsAreClimbedAndWhirlpoolsCrossedWhenUsable() {
        val falls = area(
            "~",
            "|",
            "|",
            "~",
        )
        val climb = RouteOptions(mode = MovementMode.SURF, fieldMoves = setOf(FieldMoveKind.SURF, FieldMoveKind.WATERFALL))
        val up = assertIs<Pathfinder.Result.Found>(Pathfinder(falls).to(0, 0, Node(0, 3), climb))
        val waterfall = assertIs<FieldMoveEdge>(up.route.edges.single())
        assertEquals(FieldMoveKind.WATERFALL, waterfall.move)
        assertEquals(Direction.NORTH, waterfall.direction)
        // Without Waterfall, the failure says so from the water tile below, facing north.
        val surfOnly = RouteOptions(mode = MovementMode.SURF, fieldMoves = setOf(FieldMoveKind.SURF))
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.WATERFALL, 0, 2, Node(0, 3), Direction.NORTH), Pathfinder(falls).to(0, 0, Node(0, 3), surfOnly).needs())
        // Going down needs no move: surfing into the waterfall from above slides the player to the bottom.
        val down = assertIs<Pathfinder.Result.Found>(Pathfinder(falls).to(0, 3, Node(0, 0), surfOnly))
        assertEquals(Node(0, 3), assertIs<Edge.Slide>(down.route.edges.single()).to)
        val whirl = RouteOptions(mode = MovementMode.SURF, fieldMoves = setOf(FieldMoveKind.SURF, FieldMoveKind.WHIRLPOOL))
        val across = assertIs<Pathfinder.Result.Found>(Pathfinder(area("~@~")).to(2, 0, Node(0, 0), whirl))
        assertEquals(FieldMoveKind.WHIRLPOOL, assertIs<FieldMoveEdge>(across.route.edges.single()).move)
    }

    @Test
    fun cutTreesAreCutWhenCutIsUsable() {
        val map = area(
            "#####",
            ".....",
            "#####",
        )
        val tree = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.CUT)))
        assertEquals(FieldMoveKind.CUT, Pathfinder(map, tree).to(4, 1, Node(0, 1)).needs().move)
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map, tree).to(4, 1, Node(0, 1), RouteOptions(fieldMoves = setOf(FieldMoveKind.CUT))))
        val cut = assertIs<FieldMoveEdge>(found.route.edges[1])
        assertEquals(Node(2, 1), cut.to)
        assertTrue(cut.clearsObstacle)
    }

    @Test
    fun rockClimbWallsAreClimbedUpAndDownAlongTheirAxis() {
        // A two-tile wall between a ledge of floor at 40 (top) and the floor at 0 (bottom).
        val map = area(
            "HHH",
            "#N#",
            "#N#",
            "...",
        )
        val climb = RouteOptions(fieldMoves = setOf(FieldMoveKind.ROCK_CLIMB))
        // Up: one Rock Climb from the foot (1,3) facing north, over both wall tiles, onto the top.
        val up = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(1, 0, Node(1, 3), climb))
        val wall = assertIs<FieldMoveEdge>(up.route.edges.single())
        assertEquals(FieldMoveKind.ROCK_CLIMB, wall.move)
        assertEquals(Direction.NORTH, wall.direction)
        assertEquals(listOf(Node(1, 2), Node(1, 1), Node(1, 0)), wall.tiles)
        // Down: the same move facing south (walls already climbed are walked back down when that's the way).
        val down = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(1, 3, Node(1, 0), climb))
        assertEquals(Direction.SOUTH, assertIs<FieldMoveEdge>(down.route.edges.single()).direction)
        // Without Rock Climb, the failure names the wall and where to use it from.
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.ROCK_CLIMB, 1, 2, Node(1, 3), Direction.NORTH), Pathfinder(map).to(1, 0, Node(1, 3)).needs())
        // A north-south wall isn't climbed sideways.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area(".N.")).to(2, 0, Node(0, 0), climb))
        // An east-west one is.
        assertEquals(Direction.EAST, assertIs<FieldMoveEdge>(assertIs<Pathfinder.Result.Found>(Pathfinder(area(".C.")).to(2, 0, Node(0, 0), climb)).route.edges.single()).direction)
    }

    @Test
    fun aRockClimbLandsOnTheSurfaceNextToTheWallsEnd() {
        // The tile after the wall has two surfaces (0 and 40): the climb lands on the one closest to the wall (20 → 0
        // and 40 are equally far: the lower, first one); a wall ending on a person or on water isn't climbed.
        val map = area(
            "B",
            "N",
            ".",
        )
        val climb = RouteOptions(fieldMoves = setOf(FieldMoveKind.ROCK_CLIMB))
        val up = assertIs<Pathfinder.Result.Found>(Pathfinder(map).route(Node(0, 2), climb) { it.x == 0 && it.y == 0 })
        assertEquals(Node(0, 0, 0), up.route.end)
        val person = Overlay(listOf(LiveObject(0, 0, Direction.SOUTH)))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map, person).route(Node(0, 2), climb) { it.x == 0 && it.y == 0 })
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area("~", "N", ".")).route(Node(0, 2), climb) { it.x == 0 && it.y == 0 })
    }

    @Test
    fun strengthPushesArePlannedAsAPuzzle() {
        // From the south, the boulder in the corridor must go north into the alcove to free the way east.
        val map = area(
            "##.###",
            "......",
            "##.###",
            "##.###",
        )
        val boulder = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map, boulder).to(5, 1, Node(2, 3), options))
        val route = assertIs<Route>(PushPlanner(map, boulder).route(Node(2, 3), options) { it.x == 5 && it.y == 1 })
        val push = route.edges.filterIsInstance<PushEdge>().single()
        assertEquals(Direction.NORTH, push.direction)
        assertEquals(2 to 0, push.objectTo)
        assertEquals(Node(5, 1), route.end)
        // Without Strength, no plan.
        assertEquals(null, PushPlanner(map, boulder).route(Node(2, 3), RouteOptions()) { it.x == 5 && it.y == 1 })
    }

    /**
     * The push planner follows the plain routes' rules: a route with no way back (a ledge jumped after the push) only
     * with [RouteOptions.acceptOneWay], and then with the same warnings ([RouteWarning.OneWay], the grass crossed).
     */
    @Test
    fun pushRoutesFollowTheLedgeRuleAndWarnLikePlainRoutes() {
        val map = area(
            "##.###",
            "....\".",
            "##.##v",
            "##.##.",
        )
        val boulder = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))
        // Below the ledge there is no way back: refused unless one-way routes are accepted.
        assertEquals(null, PushPlanner(map, boulder).route(Node(2, 3), options) { it.x == 5 && it.y == 3 })
        val route = assertIs<Route>(PushPlanner(map, boulder).route(Node(2, 3), options.copy(acceptOneWay = true)) { it.x == 5 && it.y == 3 })
        assertTrue(route.edges.any { it is Edge.Jump })
        assertTrue(RouteWarning.OneWay in route.warnings, route.warnings.toString())
        assertTrue(RouteWarning.CrossesTallGrass(1) in route.warnings, route.warnings.toString())
        // Above the ledge, the way back exists: no one-way warning.
        val back = assertIs<Route>(PushPlanner(map, boulder).route(Node(2, 3), options) { it.x == 5 && it.y == 1 })
        assertTrue(RouteWarning.OneWay !in back.warnings, back.warnings.toString())
    }

    /**
     * A search reaching its bound with ledges isn't "no plan": ledges open a wide field below (no way back), which
     * fills the bound before the goal at the end of the corridor is reached; the search without ledges, smaller, still
     * finds the corridor. Same rule in the push and the platform planners (both bounded searches).
     */
    @Test
    fun overTheBoundWithLedgesTheBoundedPlannersTryWithout() {
        val map = area(
            "..........",
            "v#########",
            *Array(8) { ".........." },
        )
        val goal = { n: Node -> n.x == 9 && n.y == 0 }
        val noPlatforms = object : PuzzleMechanics<List<PlatformPose>> {
            override val mechanism = PuzzleMechanism.MOVING_PLATFORM
            override val state = emptyList<PlatformPose>()
            override fun ride(state: List<PlatformPose>, x: Int, y: Int): MechanismRide<List<PlatformPose>>? = null
        }
        // The corridor is 10 places: within a bound of 12 without ledges, not with the field below the ledge.
        val pushed = assertIs<Route>(PushPlanner(map, Overlay(), maxStates = 12).route(Node(0, 0), RouteOptions(), isGoal = goal))
        assertEquals(Node(9, 0), pushed.end)
        assertTrue(pushed.edges.none { it is Edge.Jump })
        val ridden = assertIs<Route>(MechanismPlanner(map, Overlay(), noPlatforms, maxStates = 12).route(Node(0, 0), RouteOptions(), isGoal = goal))
        assertEquals(Node(9, 0), ridden.end)
        // A goal beyond the bound either way: still no plan.
        assertEquals(null, PushPlanner(map, Overlay(), maxStates = 12).route(Node(0, 0), RouteOptions()) { it.x == 9 && it.y == 9 })
    }

    /** The platform planner follows the plain routes' ledge rule too: a route with no way back only when accepted. */
    @Test
    fun platformRoutesFollowTheLedgeRule() {
        val map = area(
            "......",
            "#####v",
            "#####.",
        )
        val noPlatforms = object : PuzzleMechanics<List<PlatformPose>> {
            override val mechanism = PuzzleMechanism.MOVING_PLATFORM
            override val state = emptyList<PlatformPose>()
            override fun ride(state: List<PlatformPose>, x: Int, y: Int): MechanismRide<List<PlatformPose>>? = null
        }
        val planner = MechanismPlanner(map, Overlay(), noPlatforms)
        assertEquals(null, planner.route(Node(0, 0), RouteOptions()) { it.x == 5 && it.y == 2 })
        val route = assertIs<Route>(planner.route(Node(0, 0), RouteOptions(acceptOneWay = true)) { it.x == 5 && it.y == 2 })
        assertTrue(route.edges.any { it is Edge.Jump })
        assertTrue(RouteWarning.OneWay in route.warnings, route.warnings.toString())
    }

    @Test
    fun aBoulderJammedInADeadEndIsNotPushedThere() {
        // Corridor with the boulder: pushed east, it ends on 5,1 against the wall, the goal behind it.
        val map = area(
            "######",
            "......",
            "######",
        )
        val boulder = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))
        // The goal is beyond the boulder's last free tile: impossible, the search ends (bounded) with no plan.
        assertEquals(null, PushPlanner(map, boulder).route(Node(0, 1), options) { it.x == 5 && it.y == 1 })
        // The tile before it is reachable by pushing it to the end (three pushes).
        val route = assertIs<Route>(PushPlanner(map, boulder).route(Node(0, 1), options) { it.x == 4 && it.y == 1 })
        assertEquals(3, route.edges.count { it is PushEdge })
    }

    @Test
    fun slidingIntoAnIceBlockPushesItOn() {
        // Sliding north from 3,3 stops against the block on 3,1, which slides on to 3,0; then the player can stop on
        // 3,1 (against the block) and walk east.
        val map = area(
            "###*###",
            "...*...",
            "###*###",
            "###.###",
        )
        val block = Overlay(listOf(LiveObject(3, 1, Direction.SOUTH, iceBlock = true)))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map, block).to(6, 1, Node(3, 3)))
        val route = assertIs<Route>(PushPlanner(map, block).route(Node(3, 3), RouteOptions()) { it.x == 6 && it.y == 1 })
        val push = route.edges.filterIsInstance<PushEdge>().single()
        assertEquals(3 to 1, push.objectFrom)
        assertEquals(3 to 0, push.objectTo)
        assertEquals(Node(6, 1), route.end)
    }

    // endregion

    // region Turns (RouteOptions.turnCost)

    /** Changes of direction between consecutive edges of [edges] (what [RouteOptions.turnCost] is paid for). */
    private fun turns(edges: List<Edge>): Int =
        edges.zipWithNext().count { (a, b) -> a.endDirection != null && a.endDirection != b.direction }

    @Test
    fun aDiagonalIsWalkedAsAnLNotAZigzag() {
        val open = area(*Array(6) { "......" })
        val route = assertIs<Pathfinder.Result.Found>(Pathfinder(open).to(5, 5, Node(0, 0))).route
        assertEquals(10, route.edges.size)
        assertEquals(1, turns(route.edges), route.edges.joinToString { it.direction.name })
    }

    @Test
    fun aSlightlyLongerWayWithFewerTurnsWins() {
        // The shortest way (7 steps) is a staircase of 5 turns or more; around by the east, 9 steps and 2 turns.
        val map = area(
            "###..",
            "##...",
            "#..#.",
            "..##.",
            ".....",
        )
        val straight = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(3, 0, Node(0, 4))).route
        assertEquals(9, straight.edges.size)
        assertEquals(2, turns(straight.edges))
        // Compared by length only (turn cost 0), the staircase.
        val shortest = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(3, 0, Node(0, 4), RouteOptions(turnCost = 0))).route
        assertEquals(7, shortest.edges.size)
    }

    @Test
    fun theBicycleTurnCostsMoreThanAWalkingOne() {
        assertEquals(RouteOptions.TURN_COST, RouteOptions().turnPenalty)
        assertEquals(RouteOptions.TURN_COST, RouteOptions(mode = MovementMode.SURF).turnPenalty)
        assertEquals(RouteOptions.BIKE_TURN_COST, RouteOptions(mode = MovementMode.BIKE).turnPenalty)
        assertEquals(0, RouteOptions(mode = MovementMode.BIKE, turnCost = 0).turnPenalty)
    }

    /**
     * The cut of the search (a heading reached for a turn more than the node's cheapest isn't explored) loses nothing:
     * on random maps with walls, grass, water and ledges, the route found costs exactly as much as an exhaustive search
     * over every (node, direction) state.
     */
    @Test
    fun theTurnAwareSearchFindsTheCheapestRoute() {
        val random = kotlin.random.Random(4)
        val kinds = "......#\"~v>"
        repeat(300) {
            val rows = Array(7) { (0 until 8).map { kinds[random.nextInt(kinds.length)] }.joinToString("") }
            rows[0] = "." + rows[0].drop(1)
            val map = area(*rows)
            val options = RouteOptions(canSurf = random.nextBoolean(), acceptOneWay = true, avoidTallGrass = random.nextBoolean(), turnCost = random.nextInt(4))
            val goal = Node(random.nextInt(8), random.nextInt(7))
            val pathfinder = Pathfinder(map)
            val expected = exhaustive(pathfinder, Node(0, 0), goal, options)
            val found = pathfinder.route(Node(0, 0), options) { it.x == goal.x && it.y == goal.y }
            if (expected == null || goal == Node(0, 0)) return@repeat
            val route = assertIs<Pathfinder.Result.Found>(found, rows.joinToString("\n")).route
            val cost = route.edges.sumOf { it.cost } + turns(route.edges) * options.turnPenalty
            assertEquals(expected, cost, rows.joinToString("\n") + " → $goal $options")
        }
    }

    /** The cheapest cost from [start] to [goal] over every (node, direction) state, without any cut. */
    private fun exhaustive(pathfinder: Pathfinder, start: Node, goal: Node, options: RouteOptions): Int? {
        val dist = HashMap<Pair<Node, Direction?>, Int>()
        val queue = ArrayList<Pair<Pair<Node, Direction?>, Int>>()
        dist[start to null] = 0
        queue += (start to null) to 0
        while (queue.isNotEmpty()) {
            val next = queue.minBy { it.second }
            queue.remove(next)
            val (state, d) = next
            if (d > dist.getValue(state)) continue
            if (state.first.x == goal.x && state.first.y == goal.y && state.first != start) return d
            for (edge in pathfinder.neighbours(state.first, options)) {
                val turn = if (state.second != null && edge.direction != state.second) options.turnPenalty else 0
                val cost = d + edge.cost + turn
                val to = edge.to to edge.endDirection
                if (cost < (dist[to] ?: Int.MAX_VALUE)) {
                    dist[to] = cost
                    queue += to to cost
                }
            }
        }
        return null
    }

    // endregion
}
