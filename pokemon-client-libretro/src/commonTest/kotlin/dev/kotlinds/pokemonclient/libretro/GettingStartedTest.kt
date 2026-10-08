package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.actions.ActionError
import dev.kotlinds.pokemonclient.actions.ActionException
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.UnavailableReason
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Lookup
import dev.kotlinds.pokemonclient.data.LookupKind
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.EventFeed
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.AgentOptions
import dev.kotlinds.pokemonclient.view.AgentView
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The code of `docs/getting-started.md`, kept true by construction: each block between `// doc:` markers is the
 * snippet of the guide's section of that name (same calls, same order), run on the real game. When the API changes,
 * this test stops compiling and the guide is updated with it.
 *
 * Runs only with `POKEMON_ROM` (HeartGold US) and `LIBRETRO_CORES` (a directory holding the pinned DeSmuME core, e.g.
 * the bench's or the app's `cores/`: tests never download), skipped otherwise. A fresh data directory: no in-game
 * save, so the game boots into a new game's intro.
 */
class GettingStartedTest {

    @Test
    fun bootReadListAndAct() {
        val romPath = environmentVariable("POKEMON_ROM")
        assumeTrue(romPath != null, "POKEMON_ROM is not set: getting-started test skipped")
        val core = environmentVariable("LIBRETRO_CORES")
            ?.let { Path(it, "${LibretroCoreSpec.DESMUME.buildbotName}.${currentCorePlatform().extension}") }
            ?.takeIf { Files.exists(it) }
        assumeTrue(core != null, "LIBRETRO_CORES doesn't hold the DeSmuME core: getting-started test skipped")
        val dataDirectory = Files.createTemporaryDirectory("getting-started")
        Files.copy(core!!, Path(Files.createDirectories(Path(dataDirectory, "cores")), core.name))
        try {
            run(Path(romPath!!), dataDirectory)
        } finally {
            Files.deleteRecursively(dataDirectory)
        }
    }

    /**
     * The guide shows exactly the code run here: every `// doc:` block of this file (dedented) is in
     * `docs/getting-started.md`, character for character. Needs no ROM: it reads the two files (paths relative to this
     * module's directory, the tests' working directory).
     */
    @Test
    fun guideShowsTheTestedCode() {
        val guide = Files.readBytes(Path("../docs/getting-started.md")).decodeToString()
        val source = Files.readBytes(Path("src/commonTest/kotlin/dev/kotlinds/pokemonclient/libretro/GettingStartedTest.kt")).decodeToString()
        val blocks = Regex("""(?m)^[ \t]*// doc: (?!end)(.*)\n([\s\S]*?)\n[ \t]*// doc: end""").findAll(source).toList()
        assertTrue(blocks.size >= 9, "doc blocks found: ${blocks.size}")
        blocks.forEach { block ->
            val lines = block.groupValues[2].lines()
            val indent = lines.filter { it.isNotBlank() }.minOf { line -> line.takeWhile { it == ' ' }.length }
            val code = lines.joinToString("\n") { it.drop(indent) }.trim()
            assertTrue(code in guide, "docs/getting-started.md lacks the code of \"${block.groupValues[1]}\":\n$code")
        }
    }

    private fun run(rom: Path, dataDirectory: Path) {
        // doc: 1. Boot a ROM
        val game: PokemonGame = PokemonGames.detect(SystemFileSystem.source(rom).buffered().use { it.readByteArray() })
            ?: error("Unsupported ROM: $rom")
        val console = LibretroConsole(
            spec = LibretroCoreSpec.DESMUME, // or LibretroCoreSpec.forRom(rom, preferred = "melonds")
            rom = rom,
            dataDirectory = dataDirectory, // holds cores/, saves/ (<rom>.sav), system/
            onVideo = { frame -> /* draw frame.pixels (ARGB, both screens stacked) */ },
            onAudio = { samples, frames -> /* play interleaved stereo samples */ },
        )
        // doc: end
        assertEquals("Pokémon HeartGold (USA)", game.name)
        console.use {
            // doc: 2. Run frames: who advances time
            val recorder = Recorder(game) // turns what the game shows into events (texts, level ups...)
            lateinit var scope: ActionScope
            scope = ActionScope(console, game.inputProbe, onFrame = { recorder.onFrame(console.frame) { scope.memory() } })
            scope.step(600) // nothing moves unless someone steps: ~10 s of game
            // doc: end
            assertEquals(600L, console.frame)

            // doc: 3. Read the typed state
            val state: GameState = game.state(scope.memory())
            when (val screen = state.screen) {
                is Screen.Intro -> println("intro: ${screen.detail}, goes on with ${screen.goesOnWith}")
                is Screen.Selectable -> println("menu: ${screen.entries.map { it.id }}, cursor ${screen.cursor}")
                is Screen.Overworld -> println("walking on ${state.field?.mapName}")
                else -> println("screen ${screen.awaiting}")
            }
            println("party: ${state.party.map { "${it.species.name} L${it.level}" }}")
            // doc: end
            assertIs<Screen.Intro>(state.screen)
            assertTrue(state.party.isEmpty(), "a new game has no party")

            // doc: 4. List and execute actions (listing)
            val registry = ActionRegistry.of()
            val available = registry.available(state, ActionMode.ASSISTED, game)
            available.forEach { println("${it.name} ${it.choices}") }
            registry.unavailable(state, ActionMode.ASSISTED, game).forEach { println("${it.name}: ${it.reason} ${it.detail}") }
            // doc: end
            assertTrue(available.any { it.name == "wait" } && available.any { it.name == "press" }, "available: ${available.map { it.name }}")
            assertTrue(available.none { it.name == "run" }, "no `run` out of battle")

            // doc: 4. List and execute actions (execution)
            when (val outcome = registry.executeAndSettle(GameAction.Wait(frames = 60), scope, game)) {
                is ActionOutcome.Done -> println("done: ${outcome.detail}")
                is ActionOutcome.Failed -> println("failed: ${outcome.error.code} ${outcome.error.message}")
            }
            val refused = registry.execute(GameAction.Run, scope, game) // fleeing outside a battle
            val refusal = (refused as ActionOutcome.Failed).error
            if (refusal is ActionError.Unavailable) println("${refusal.reason}: ${refusal.detail}") // WRONG_SCREEN: ...
            // doc: end
            assertIs<ActionError.Unavailable>(refusal)
            assertEquals(UnavailableReason.WRONG_SCREEN, refusal.reason)
            assertTrue(console.frame >= 660L)

            // doc: 4. From JSON
            val wire = Json.parseToJsonElement("""{"type": "press", "button": "start"}""").jsonObject
            val action: GameAction = registry.parse(wire, ActionMode.ASSISTED).getOrElse { failure ->
                val parseError = (failure as ActionException).error // INVALID_PARAM: unknown type / parameter / value
                error("${parseError.code}: ${parseError.message}")
            }
            val pressed = registry.executeAndSettle(action, scope, game)
            val typo = registry.parse(Json.parseToJsonElement("""{"type": "press", "buton": "a"}""").jsonObject, ActionMode.ASSISTED)
            println(typo.exceptionOrNull()?.message) // Unknown parameter `buton` for press: use `button` (The button)
            // doc: end
            assertEquals(GameAction.Press(Button.START), action)
            assertIs<ActionOutcome.Done>(pressed)
            val typoError = (typo.exceptionOrNull() as ActionException).error
            assertIs<ActionError.UnknownParameter>(typoError)
            assertEquals("INVALID_PARAM", typoError.code)

            // doc: 4. Chains
            val chain = runBlocking {
                ChainRunner.forAgent(
                    recorder,
                    observe = { game.state(scope.memory()) },
                    execute = { step, _ -> registry.executeAndSettle(step, scope, game) },
                    settle = { ActionRegistry.settleBetweenSteps(scope, game) },
                ).run(listOf(GameAction.Wait(frames = 30), GameAction.Wait(frames = 30)))
            }
            println("performed ${chain.performed}, failed ${chain.failed}, stopped ${chain.stop?.code}, not done ${chain.skipped.map { it.key }}")
            // doc: end
            assertEquals(listOf("wait(30)", "wait(30)"), chain.performed)
            assertEquals(null, chain.failed)

            // doc: 6. The agent's view (JSON)
            val view = AgentView(game, registry) // one per agent: it remembers what the agent has seen
            val feed = EventFeed(recorder.log) // the events the agent hasn't been given yet
            val json = view.describe(
                game.state(scope.memory()),
                feed.take().events,
                AgentOptions(knowledge = KnowledgeLevel.POKEDEX),
                AgentView.Detail.STANDARD,
            )
            println(json) // {"screen": ..., "actions": [{"type": "wait", ...}, ...], ...}
            // doc: end
            assertNotNull(json["screen"])
            val types = (json["actions"] as JsonArray).map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertTrue("press" in types && "wait" in types, "view actions: $types")

            // doc: 7. Lookups
            val data = game.data ?: error("no game data for ${game.name}")
            val pikachu = Lookup(data, KnowledgeLevel.POKEDEX).lookup(LookupKind.SPECIES, "species:25")
            val hidden = Lookup(data, KnowledgeLevel.NONE).lookup(LookupKind.SPECIES, "Pikachu")
            println(pikachu.getOrThrow()) // types, base stats, abilities, evolutions...
            println(hidden.exceptionOrNull()?.message) // SPECIES needs the knowledge level POKEDEX
            // doc: end
            assertTrue(pikachu.isSuccess)
            assertTrue(hidden.isFailure)
        }
    }
}
