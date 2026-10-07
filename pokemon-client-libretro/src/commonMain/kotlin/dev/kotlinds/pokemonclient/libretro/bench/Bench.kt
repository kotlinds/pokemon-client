package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.games.gen4.Gen4Pokemon
import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.games.gen4.Gen4WorldSource
import dev.kotlinds.pokemonclient.games.hgss.HgssLoadedMap
import dev.kotlinds.pokemonclient.games.hgss.HgssData
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.hgss.HgssReader
import dev.kotlinds.pokemonclient.games.hgss.HgssMemory
import dev.kotlinds.pokemonclient.games.hgss.HgssGame
import dev.kotlinds.pokemonclient.games.hgss.HgssFishing
import dev.kotlinds.pokemonclient.games.hgss.FishingState
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.actions.ActionException
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.view.AgentOptions
import dev.kotlinds.pokemonclient.view.AgentView
import dev.kotlinds.pokemonclient.runtime.ActionScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.libretro.Files
import dev.kotlinds.pokemonclient.libretro.encodePng
import dev.kotlinds.pokemonclient.libretro.environmentVariable
import dev.kotlinds.pokemonclient.libretro.fmt
import dev.kotlinds.pokemonclient.libretro.gzip
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * Development bench: drives the game headless (no window, no sound) from a script, to check decoders and actions
 * on real screens, take screenshots and capture RAM fixtures for tests.
 *
 * ```
 * ./gradlew :pokemon-client-libretro:bench -PbenchArgs="<data dir>|<out dir>|<command>|..."
 * ```
 * Environment: `POKEMON_ROM` (ROM path), `EMULATOR_CORE` (desmume / melonds), `BENCH_WINDOW=1` (watch it live). The data directory holds `cores/`,
 * `saves/` (the in-game save is loaded at boot) and `system/`, like the app's.
 *
 * Commands (one per argument):
 * - `boot[:frames]`: from power-on, presses A until the overworld (continues the saved game);
 * - `load:<file>` / `save:<file>`: loads / saves a save state (relative to the out dir);
 * - `step:<n>`: emulates n frames; `tap:<BUTTON>[x<n>]`: self-checking taps; `hold:<B1+B2...>:<n>`: holds buttons n frames; `touch:<x>,<y>`: touches the bottom screen;
 * - `trace:<B1+B2...|none>:<held>:<total>`: holds the buttons `held` frames, then nothing, `total` frames in all, printing
 *   each frame where the screen, the map, the tile, the facing or the movement changes; `fieldtrace:on|off`: prints the
 *   same (with the buttons held) on every frame while the commands run, actions included (how a walk takes a warp);
 * - `until:<kind>:<frames>`: steps until the screen kind starts with `kind` (e.g. `overworld`, `dialogue`);
 * - `shot:<name>`: PNG of both screens; `state`: prints the decoded GameState; `screen`: prints the screen only;
 *   `flyhint:<map id>`: the fly suggestion for that map from here ([dev.kotlinds.pokemonclient.actions.FlyAdvisor]);
 *   `party`: one line per party Pokémon (id, level, HP, status, moves with PP); `healparty[:<item id>]`: Revive + Full Restore (or that item, unless a status needs curing) where needed (field); `puzzle`: prints the position and the map puzzle (switches, shutters, teleports);
 * - `act:<json>`: executes a typed action through the action registry (e.g. `act:{"type":"choose","entry":"option:6"}`;
 *   a long one prints its progress about every 5 s of game time, like the app's progress notifications), then lets the
 *   game settle like an agent's step in the app and the MCP ([ActionRegistry.executeAndSettle]: frames only, no button,
 *   until the game waits for input, for at most `max(120, 1800 − the frames the action used)` frames: a long action
 *   leaves less time to settle, never less than 120);
 * - `chain:[<json>, <json>...]`: an action and its `then` steps run like an agent's chain in the app and the MCP
 *   (`ChainRunner.forAgent`: each step settling like `act`; the chain stops when an opponent changes or faints, one of
 *   the player's battling Pokémon faints, a `run` fails, a step isn't offered on the screen reached; battle steps are
 *   dropped once the battle is over; under [dev.kotlinds.pokemonclient.actions.ChainLimits.AGENT]: no further step
 *   after 20 s without any change in the game, nor after 90 s in all); prints what was performed, not done or dropped;
 *   `actions`: lists the actions available now; `solve:on|off`: whether walks solve movement puzzles by themselves
 *   (AgentOptions.solvePuzzles); `reveal:on|off`: whether the agent has a walkthrough (AgentOptions.knowledge: actions
 *   may use hidden items, the view shows them, the story goals); `hide:on|off`: whether where the ways out lead is
 *   hidden (AgentOptions.hideDestinations: `mapview`, `view` and `go_to` follow it); `view`: exactly what an agent
 *   reads now ([dev.kotlinds.pokemonclient.view.AgentView], like the app: the events since the previous `view` /
 *   `partyfx`, the state, the battle estimates, the map, the actions);
 * - `log`: prints the events recorded since the previous `log` (texts shown, screen changes, level ups...);
 * - `ram:<name>`: writes the full main RAM; `fixture:<name>`: writes a sparse RAM fixture (only the bytes the
 *   decoders read) for unit tests.
 * - `watch:on|off`: after every frame, prints the raw party reading and what the state shows when they change
 *   (`BENCH_WATCH_HEX=1` adds the raw bytes; `BENCH_WATCH_FIXTURE=<prefix>` [+ `BENCH_WATCH_FIXTURE_SLOT=n`] saves
 *   fixtures of frames where a party slot is mid-rewrite; `BENCH_WATCH_FRAMES=<from>-<to>[:prefix]` saves a fixture of
 *   every frame in that range); `rawmon`: raw party bytes; `box:<n>`: PC box n;
 * - `pace:<n>`: walks one tile left then right, n times, until the phone rings (or the overworld is left);
 * - `record:on|off`: runs the app's Recorder on every frame; `events`: prints its events (screen changes left out).
 * - `msgtrace:<n>` / `mashtrace:<n>`: steps n frames (mashing A one frame in four) printing every change of the battle
 *   message and ball shakes; `truth:on` records the battle message of every frame, `truth:check` compares it with
 *   the recorder's battle messages (what was shown but not recorded), `truth:dump:<name>` writes it to `<name>.trace`
 *   (run-length encoded in tests); `autobattle:<n>[,move:<id>|move:best][,heal:<item id>[+<item id>...]]` plays n decisions of a battle like an agent and checks
 *   (`move:best`: the move of highest power x type effectiveness x accuracy, the party member with the best such
 *   move when one must be sent in, and a healing item (`heal:`, the first one left; a Full Restore by default) when the acting Pokémon is low on HP).
 * - `pausemusic:<name>[:<play>,<pause>,<resume>]` / `pausemusicstats:<pauses>[:<frames>[:<max delay>[:<buttons>]]]` / `pausemusicload:<file>` / `pausemusicdriver` /
 *   `pausemusicscan:<buttons>:<frames>[,<every>,<pause frames>,<max delay>,<save refused>]` (a pause every few frames
 *   while walking, e.g. across a map edge) /
 *   `pausemusicfixtures:<prefix>[:<frames>]`: music during pauses, end to
 *   end with a shadow core (WAVs, guards, continuity, main RAM checks; see [PauseMusicCheck]).
 * - `trip:<flee|fight>:<go_to json>`: a whole trip played like an agent would: `go_to` again after every interruption,
 *   wild battles fled (`flee`) or won with the first move (`fight`), trainer battles won with the first move, texts
 *   read; prints the frames until the player stands at the destination with the control back, and the battles met
 *   (to compare routes: see `Pathfinder`'s step weights).
 *
 * Common code: each platform only starts it from its entry point (`BenchMain.kt` on the JVM), and runs it headless when
 * it has no window ([openBenchViewer]).
 */
fun runBench(args: List<String>) {
    require(args.size >= 3) { "usage: <data dir>|<out dir>|<command>|..." }
    val data = Path(args[0])
    val out = Files.createDirectories(Path(args[1]))
    val rom = Path(environmentVariable("POKEMON_ROM") ?: error("POKEMON_ROM is not set"))
    val game = PokemonGames.detect(Files.readBytes(rom)) ?: error("Unsupported ROM $rom")
    // BENCH_WINDOW=1 opens a live, muted window to watch the commands run at the console's speed (where there is one).
    val viewer = if (environmentVariable("BENCH_WINDOW") == "1") openBenchViewer("Bench — ${rom.name}") else null
    val spec = LibretroCoreSpec.forRom(rom, environmentVariable("EMULATOR_CORE"))
    val audioTap = AudioTap() // audio is only collected by the `pausemusic` checks
    val console = LibretroConsole(spec, rom, data, onVideo = { viewer?.show(it) }, onAudio = audioTap::onAudio)
    val pauseMusic = PauseMusicCheck(console, audioTap, spec, rom, data, out)
    val bench = Bench(console, game, out, rom, pauseMusic)
    try {
        args.drop(2).forEach { command ->
            println("> $command")
            bench.run(command)
        }
    } finally {
        pauseMusic.close()
        console.close()
        viewer?.close()
    }
}

private class Bench(
    private val console: LibretroConsole,
    private val game: PokemonGame,
    private val out: Path,
    private val romPath: Path,
    private val pauseMusic: PauseMusicCheck,
) {

    /** Records every event (text shown, screen changes...) like the app does, printed by the `log` command. */
    private val recorder = dev.kotlinds.pokemonclient.runtime.Recorder(game)
    private var logCursor = 0L

    /** `watch:on` prints, after every emulated frame, the raw party reading and what the state exposes when they change. */
    private var watching = false
    private var lastWatch: String? = null
    /** `autobattle`: every battle message read on every frame (the ground truth the recorder is checked against). */
    private var truth: MutableList<String>? = null

    /** `fieldtrace:on`: the last field line printed (see [traceField]), null while off. */
    private var fieldTrace: String? = null

    private val scope: ActionScope = ActionScope(console, game.inputProbe, onFrame = {
        if (watching) watchParty()
        if (fieldTrace != null) traceField()
        truth?.let { seen ->
            val message = runCatching { game.state(scope.memory()).battle?.message }.getOrNull()
            truthFrames += message
            if (message != null && message != seen.lastOrNull()) {
                // A half-written read is a prefix of the next one: keep the complete text only.
                if (seen.isNotEmpty() && message.startsWith(seen.last())) seen[seen.size - 1] = message else seen += message
            }
        }
        recorder.onFrame(console.frame) { scope.memory() }
    }, onProgress = { progress ->
        // Like the app: the recorder's clock takes it; printed about every 5 s of game time (the app's notification
        // cadence), so a long `go_to` shows how it goes.
        recorder.progress.report(progress)
        if (console.frame - lastProgressPrint >= PROGRESS_PRINT_FRAMES) {
            lastProgressPrint = console.frame
            println("  progress (frame ${console.frame}): ${progress.text}")
        }
    })

    /**
     * `fieldtrace:on`: prints the frame, the buttons held, the screen and the player's map, tile, facing and movement
     * whenever one of them changes (how an action walks into a warp, frame by frame).
     */
    private fun traceField() {
        val s = runCatching { game.state(scope.memory()) }.getOrNull() ?: return
        val f = s.field
        val held = runCatching { game.inputProbe.heldButtons(scope.memory()) }.getOrDefault(emptySet())
        val line = "${held.joinToString("+").ifEmpty { "-" }} ${s.screen.kind}/${s.screen.awaiting} ${f?.mapId} ${f?.x},${f?.y} ${f?.facing} moving=${f?.moving}"
        if (line != fieldTrace) println("  [${console.frame}] $line")
        fieldTrace = line
    }

    /** Frame of the last progress printed (see the scope's `onProgress`). */
    private var lastProgressPrint = Long.MIN_VALUE / 2
    private val registry = ActionRegistry.of()

    /**
     * What the agent may know and do (`solve:on|off`, `reveal:on|off`, `hide:on|off`), like the app's settings: the
     * actions and the view follow the same options. A walkthrough by default (hidden items usable, as the recipes allow
     * by default).
     */
    private var options = AgentOptions(knowledge = KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)

    /** What an agent reads (`view`, `partyfx`), built like the app's, with the events it hasn't been given yet. */
    private val agentView = AgentView(game, registry)
    private val agentEvents = dev.kotlinds.pokemonclient.runtime.EventFeed(recorder.log)

    /** The agent's view now ([AgentView]), with the events since the previous one. */
    private fun agentView(): kotlinx.serialization.json.JsonObject =
        agentView.describe(game.state(scope.memory()), agentEvents.take().events, options, AgentView.Detail.STANDARD)

    /** Walks back and forth (one tile left, one right) up to [times] times, until the phone rings or the overworld is left. */
    private fun pace(times: Int) {
        repeat(times) { i ->
            for (button in listOf(Button.LEFT, Button.RIGHT)) {
                scope.step(16, InputFrame(setOf(button)))
                scope.step(4)
                val screen = game.state(scope.memory()).screen
                if (screen !is Screen.Overworld || screen.incomingCall != null) {
                    println("  after ${i + 1} paces: $screen")
                    return
                }
            }
        }
        println("  no call after $times paces")
    }

    fun run(command: String) {
        val name = command.substringBefore(':')
        val arg = command.substringAfter(':', "")
        // Commands reading HeartGold / SoulSilver structures directly (raw reader, party bytes, fishing, loaded map).
        if (name in HGSS_ONLY && game !is HgssGame) error("`$name` reads HeartGold / SoulSilver RAM directly: not available for ${game.name}")
        when (name) {
            "boot" -> boot(arg.toIntOrNull() ?: 6000)
            "load" -> check(console.loadState(Files.readBytes(Path(out, arg)))) { "state rejected: $arg" }
            "save" -> Files.writeBytes(Path(out, arg), console.saveState())
            "step" -> scope.step(arg.toInt())
            "tap" -> {
                val button = Button.valueOf(arg.substringBefore('x').uppercase())
                repeat(arg.substringAfter('x', "1").toInt()) { println("  ${scope.tap(button)}") }
            }
            "fieldtrace" -> fieldTrace = if (arg == "on") "" else null
            "hold" -> arg.split(':').let { (b, n) -> scope.step(n.toInt(), InputFrame(b.split('+').map { Button.valueOf(it.uppercase()) }.toSet())); scope.step(2) }
            // trace:<B1+B2...|none>:<held frames>:<total frames>: holds the buttons, then nothing, printing every frame
            // where the map, the position, the facing, the movement or the screen changes (warps, steps, fades).
            "trace" -> arg.split(':').let { (b, n, total) ->
                val held = if (b == "none") emptySet() else b.split('+').map { Button.valueOf(it.uppercase()) }.toSet()
                var last = ""
                repeat(total.toInt()) { i ->
                    scope.step(1, if (i < n.toInt()) InputFrame(held) else InputFrame(emptySet()))
                    val s = game.state(scope.memory())
                    val f = s.field
                    val line = "${s.screen.kind} ${f?.mapId} ${f?.x},${f?.y} ${f?.facing} moving=${f?.moving}"
                    if (line != last) println("  +${i + 1}${if (i < n.toInt()) " held" else ""}: $line")
                    last = line
                }
            }
            "raw" -> dev.kotlinds.pokemonclient.games.hgss.HgssReader(scope.memory()).read()?.let { st ->
                println("  mode=${st.mode} detail=${st.modeDetail} awaiting=${st.awaitingInput} fading=${st.fading}")
                println("  loc=${st.location?.let { "${game.mapName(it.mapId)} ${it.x},${it.z} ${it.facing}" }}")
                println("  app=${st.app} engagedTrainer=${st.engagedTrainer}")
                st.surroundings?.bgEvents?.forEach { println("  bg $it") }
                st.surroundings?.grid?.let { g -> println("  origin ${g.originX},${g.originZ}"); g.rows.forEachIndexed { i, row -> println("  | ${g.originZ + i} $row") } }
                st.surroundings?.objects?.forEach { println("  obj ${it.label} ${it.x},${it.z} id=${it.id} zone=${it.mapId} sprite=${it.sprite} flag=${it.eventFlag} script=${it.scriptId} hidden=${it.hidden} move=${it.movement}") }
            }
            "touch" -> arg.split(',').let { (x, y) -> scope.touch(TouchPoint(x.toInt(), y.toInt())) }
            "until" -> {
                val (kind, frames) = arg.split(':')
                val met = scope.stepUntil(frames.toInt()) { game.state(it).screen.kind.startsWith(kind) }
                println("  ${if (met) "reached" else "NOT reached"} $kind at frame ${console.frame}")
            }
            "shot" -> console.framebuffer()?.let { Files.writeBytes(Path(out, "$arg.png"), encodePng(it)) }
            "state" -> println(game.state(scope.memory()).toString().replace(", ", ",\n  "))
            "screen" -> println("  " + game.state(scope.memory()).screen)
            // `healparty[:<item>]`: between battles, a Revive on each fainted Pokémon, then a Full Restore (or <item>) on each one hurt.
            "healparty" -> game.state(scope.memory()).party.filter { !it.isEgg }.forEach { mon ->
                if (mon.hp == 0) run("""act:{"type":"use_item","item":"item:$REVIVE","target":"${mon.id}"}""")
                val now = game.state(scope.memory()).party.firstOrNull { it.id == mon.id }
                if (now != null && (now.hp < now.maxHp || now.status != null)) {
                    val item = if (now.status != null) FULL_RESTORE else arg.toIntOrNull() ?: FULL_RESTORE
                    run("""act:{"type":"use_item","item":"item:$item","target":"${mon.id}"}""")
                }
            }
            // `flyhint:<map id>`: the fly suggestion for that map from here (FlyAdvisor), and how long it took.
            "flyhint" -> {
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                val suggestion = dev.kotlinds.pokemonclient.actions.FlyAdvisor(game).suggest(game.state(scope.memory()), arg.toInt())
                println("  ${suggestion?.text ?: "no suggestion"} (${mark.elapsedNow()})")
            }
            "party" -> game.state(scope.memory()).party.forEach { println("  ${it.id} ${it.displayName} Lv${it.level} ${it.hp}/${it.maxHp} ${it.status ?: ""} " + it.moves.joinToString { m -> "${m.move.name} (move:${m.move.id.value}) ${m.pp}/${m.maxPp}" }) }
            "puzzle" -> game.state(scope.memory()).field.let { f ->
                println("  ${f?.mapName} (${f?.mapId}) at ${f?.x},${f?.y}")
                f?.puzzle?.let { p ->
                    println("  puzzle ${p.kind}: ${p.rule}")
                    p.switches.forEach { println("    $it") }
                    p.barriers.forEach { println("    $it") }
                    p.teleports.forEach { println("    $it") }
                    p.indicators.forEach { println("    $it") }
                    p.surfaces.forEach { println("    $it") }
                    if (p.unmodeled != null) println("    unmodeled: ${p.unmodeled}")
                    p.herds.forEach { println("    $it") }
                    p.boulderHoles.forEach { println("    $it") }
                    p.iceBlocks.forEach { println("    $it") }
                    p.stepAside.forEach { println("    $it") }
                    p.platforms.forEach { pl -> println("    ${pl.id} pivot ${pl.pivot.x},${pl.pivot.y} r${pl.rotation} " + pl.triggers.joinToString { "${it.tile.x},${it.tile.y}=${it.effect}${if (it.possible) "" else "(blocked)"}" }) }
                } ?: println("  no puzzle")
            }
            "ram" -> Files.writeBytes(Path(out, "$arg.ram"), ram())
            "fixture" -> fixture(arg)
            "world" -> world(arg)
            "mapview" -> game.state(scope.memory()).field?.let { f ->
                game.world?.areaOf(f.mapId)?.let { area -> MapView.render(area, f, game::mapName, world = game.world, hideDestinations = options.hideDestinations) }
            }?.forEach { (k, v) -> println("  $k: " + (v as? kotlinx.serialization.json.JsonArray)?.joinToString("\n    ", "\n    ") { it.toString().trim('"') }.orEmpty().ifEmpty { v.toString() }) }
            "area" -> area(arg.split(',').map { it.trim().toInt() })
            "tiles" -> arg.split(',').map { it.trim().toInt() }.let { (x0, x1, y) ->
                val f = game.state(scope.memory()).field
                val ar = f?.let { game.world?.areaOf(it.mapId) }
                (x0..x1).forEach { x -> println("  $x,$y ${ar?.tile(x, y)} zone=${ar?.zoneAt(x, y)}") }
            }
            "trace" -> trace(arg.toInt())
            "msgtrace" -> msgTrace(arg.toInt())
            "mashtrace" -> msgTrace(arg.toInt(), mash = true)
            "truth" -> when {
                arg == "on" -> truthOn()
                // One line per frame: the battle message read that frame (escaped), empty when none. For replay tests.
                arg.startsWith("dump:") -> Files.writeBytes(
                    Path(out, arg.removePrefix("dump:") + ".trace"),
                    (truthFrames.joinToString("\n") { m -> m?.replace("\\", "\\\\")?.replace("\n", "\\n") ?: "" } + "\n").encodeToByteArray(),
                )
                else -> truthCheck()
            }
            "autobattle" -> arg.split(',').let { parts ->
                val options = parts.drop(1)
                autoBattle(
                    parts[0].toInt(), options.firstOrNull { it.startsWith("move:") },
                    options.firstOrNull { it.startsWith("heal:") }?.removePrefix("heal:")?.split('+')?.map { it.toInt() } ?: listOf(FULL_RESTORE),
                )
            }
            "rawmon" -> HgssReader(scope.memory(), romVersion).partyRaw().forEachIndexed { i, b ->
                println("  slot $i: " + b.joinToString("") { "%02x".fmt(it) })
            }
            "box" -> HgssReader(scope.memory(), romVersion).boxRaw(arg.toInt()).forEachIndexed { i, b ->
                Gen4Pokemon.decode(b)?.let { m ->
                    println("  $i: mon:%08x.%08x species ${m.species} ${HgssData.speciesName(m.species)} exp ${m.exp}".fmt(m.personality, m.otId))
                }
            }
            "events" -> recorder.log.since(0).filterNot { it is dev.kotlinds.pokemonclient.state.GameEvent.ScreenChanged }.forEach { println("  $it") }
            "watch" -> { watching = arg != "off"; lastWatch = null }
            "steps" -> steps(Button.valueOf(arg.substringBefore('x').uppercase()), arg.substringAfter('x', "1").toInt())
            "where" -> HgssReader(scope.memory(), romVersion).read()?.let { st ->
                println("  ${st.mode} ${st.modeDetail} at ${st.location?.x},${st.location?.z} facing ${st.location?.facing} map ${st.location?.let { game.mapName(it.mapId) }}")
                st.surroundings?.bgEvents?.forEach { println("    bg $it") }
                st.surroundings?.grid?.let { g -> g.rows.forEachIndexed { i, r -> println("    ${g.originZ + i}\t$r") }; println("    x0=${g.originX}") }
            }
            "fish" -> fish(arg.toInt())
            "pace" -> pace(arg.toInt())
            "scr" -> println(describe(game.state(scope.memory()).screen))
            "walk" -> walk(arg.split(',').map { Button.valueOf(it.trim().uppercase()) })
            "cur" -> println(describe(game.state(scope.memory()).screen).lineSequence().first())
            "act" -> {
                val action = registry.parse(Json.parseToJsonElement(arg).jsonObject, options.mode).getOrElse { error ->
                    // Refused like the agent would see it, and the script goes on.
                    println("  refused: " + ((error as? ActionException)?.error?.let { "${it.code} ${it.message}" } ?: error.toString()))
                    return
                }
                val startFrame = console.frame
                // Like the app: the game settles after every action.
                val outcome = registry.executeAndSettle(action, scope, game, options.actionSettings)
                println("  $outcome (${console.frame - startFrame} frames, settled)")
                println("  " + game.state(scope.memory()).screen)
            }
            // `chain:[{json}, {json}...]`: an action and its `then` steps run exactly like the app's (ChainRunner.forAgent: the
            // same stop rules and limits), each step settling like `act`.
            "chain" -> {
                val steps = Json.parseToJsonElement(arg) as kotlinx.serialization.json.JsonArray
                val actions = steps.map { registry.parse(it.jsonObject, options.mode).getOrThrow() }
                val result = kotlinx.coroutines.runBlocking {
                    ChainRunner.forAgent(
                        recorder,
                        observe = { game.state(scope.memory()) },
                        execute = { action, _ -> registry.executeAndSettle(action, scope, game, options.actionSettings) },
                        settle = { ActionRegistry.settleBetweenSteps(scope, game) },
                    ).run(actions)
                }
                println("  performed ${result.performed} details ${result.details}")
                result.failed?.let { (a, e) -> println("  failed ${a.key}: ${e.code} ${e.message}") }
                if (result.skipped.isNotEmpty()) println("  not_done ${result.skipped.map { it.key }} code=${result.stop?.code} reason=${result.stop?.message}")
                if (result.dropped.isNotEmpty()) println("  dropped ${result.dropped.map { it.key }} code=${result.droppedBecause?.code}")
                println("  " + game.state(scope.memory()).screen)
            }
            // `partyfx`: the battle's estimates exactly as an agent reads them ([AgentView]: effectiveness, switch candidates,
            // what is known of the opponents, catch chance), after the battlers.
            "partyfx" -> {
                val battle = game.state(scope.memory()).battle
                if (battle == null || game.data == null) println("  no battle / no game data") else {
                    // The game's decision, known before the battle leaves the screen (BattleState.outcome).
                    println("  outcome ${battle.outcome ?: "undecided"} message ${battle.message?.replace('\n', ' ')}")
                    battle.battlers.forEach { println("  ${it.ref.wire} ${it.species.name} L${it.level} ${it.hp}/${it.maxHp} volatile=${it.volatile}") }
                    val view = agentView()
                    listOf("effectiveness", "party_effectiveness", "opponents_known").forEach { key ->
                        (view[key] as? kotlinx.serialization.json.JsonArray)?.forEach { println("  $key ${it.toString().trim('"')}") }
                    }
                    view["catch"]?.let { println("  catch $it") }
                }
            }
            "trip" -> trip(arg.substringBefore(':'), arg.substringAfter(':'))
            "log" -> {
                recorder.log.since(logCursor).forEach { println("  $it") }
                logCursor = recorder.log.lastSeq
            }
            "solve" -> options = options.copy(solvePuzzles = arg != "off")
            "reveal" -> options = options.copy(knowledge = if (arg != "off") KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH else KnowledgeLevel.POKEDEX)
            "hide" -> options = options.copy(hideDestinations = arg != "off")
            "view" -> agentView().forEach { (k, v) -> println("  $k: $v") }
            "actions" -> registry.available(game.state(scope.memory()), options.mode).forEach { println("  $it") }
            "pausemusic" -> pauseMusic.check(arg)
            "pausemusicstats" -> pauseMusic.stats(arg)
            "pausemusicload" -> pauseMusic.load(arg)
            "pausemusicfixtures" -> pauseMusic.fixtures(arg)
            "pausemusicdriver" -> pauseMusic.driver()
            "pausemusicmenu" -> pauseMusic.menu(arg) { describe(game.state(scope.memory()).screen).lineSequence().first() }
            "pausemusicscan" -> pauseMusic.scan(arg) {
                val state = game.state(scope.memory())
                "${state.field?.let { "${it.mapName} ${it.x},${it.y}" } ?: "-"} " + describe(state.screen).lineSequence().first()
            }
            "pausemusicintro" -> pauseMusic.intro(arg) { describe(game.state(scope.memory()).screen).lineSequence().first() }
            else -> error("unknown command $command")
        }
    }

    /** `area:x0,y0,x1,y1`: the ROM tiles of the current area as ASCII, with the live objects (B boulder, I ice, T tree, R rock, P person, @ you). */
    private fun area(box: List<Int>) {
        val state = game.state(scope.memory())
        val field = state.field ?: return println("  not in the field")
        val area = game.world?.areaOf(field.mapId) ?: return println("  no area")
        val (x0, y0, x1, y1) = box
        println("  x ${x0}..${x1} (tens: ${(x0..x1).joinToString("") { ((it / 10) % 10).toString() }})")
        println("  units      ${(x0..x1).joinToString("") { (it % 10).toString() }}")
        for (y in y0..y1) {
            val row = (x0..x1).joinToString("") { x ->
                val o = field.objects.firstOrNull { it.x == x && it.y == y && it.kind != dev.kotlinds.pokemonclient.state.FieldObjectKind.FOLLOWER }
                when {
                    field.x == x && field.y == y -> "@"
                    o?.obstacle != null -> when (o.obstacle!!) {
                        dev.kotlinds.pokemonclient.state.ObstacleKind.BOULDER -> "B"
                        dev.kotlinds.pokemonclient.state.ObstacleKind.ICE_BLOCK -> if (o.facing == dev.kotlinds.pokemonclient.Direction.SOUTH) "I" else "i"
                        dev.kotlinds.pokemonclient.state.ObstacleKind.CUT_TREE -> "T"
                        dev.kotlinds.pokemonclient.state.ObstacleKind.SMASH_ROCK -> "R"
                    }
                    o != null -> "P"
                    area.warps.any { it.x == x && it.y == y } -> "W"
                    else -> when (val t = area.tile(x, y)) {
                        null -> " "
                        else -> when (val k = t.kind) {
                            is dev.kotlinds.pokemonclient.world.TileKind.Water -> if (k.surfable) "~" else "#"
                            dev.kotlinds.pokemonclient.world.TileKind.Whirlpool -> "%"
                            dev.kotlinds.pokemonclient.world.TileKind.Waterfall -> "|"
                            dev.kotlinds.pokemonclient.world.TileKind.Ice -> "*"
                            dev.kotlinds.pokemonclient.world.TileKind.TallGrass -> "\""
                            is dev.kotlinds.pokemonclient.world.TileKind.Ledge -> when (k.jump) {
                                dev.kotlinds.pokemonclient.Direction.SOUTH -> "v"; dev.kotlinds.pokemonclient.Direction.NORTH -> "^"
                                dev.kotlinds.pokemonclient.Direction.WEST -> "<"; dev.kotlinds.pokemonclient.Direction.EAST -> ">"
                            }
                            else -> if (t.blocked) "#" else "."
                        }
                    }
                }
            }
            println("  ${y.toString().padStart(5)}      $row")
        }
    }

    private fun watchParty() {
        val memory = scope.memory()
        // The raw HGSS party next to the common state: HGSS only (another game has nothing to compare with).
        val version = hgssVersion ?: return
        val raw = HgssReader(memory, version).read() ?: return
        val state = game.state(memory)
        val rawLine = raw.party.joinToString(" | ") { "${it.slot}:${it.speciesName} L${it.level} ${it.hp}/${it.maxHp} x${it.exp} ${it.status} m=${it.moves.joinToString("/") { m -> "${m.id}:${m.pp}" }}${if (it.checksumOk) "" else " CS!"}${if (it.plausible) "" else " IMPL"}" }
        val shown = state.party.joinToString(" | ") { "${it.slot}:${it.species.name} L${it.level} ${it.hp}/${it.maxHp} m=${it.moves.joinToString("/") { m -> "${m.move.id.value}:${m.pp}" }}" }
        val line = "${describe(state.screen, oneLine = true)}\n    raw  $rawLine\n    show $shown" + state.warnings.joinToString("") { "\n    warn ${it.detail}" }
        if (line != lastWatch) println("  [${console.frame}] $line")
        lastWatch = line
        saveTornFixture(memory)
        // `BENCH_WATCH_FRAMES=<from>-<to>[:prefix]`: a fixture of every frame in that range (a short glitch to replay in a test).
        environmentVariable("BENCH_WATCH_FRAMES")?.let { spec ->
            val (from, to) = spec.substringBefore(':').split('-').map { it.toLong() }
            if (console.frame in from..to) fixture("${spec.substringAfter(':', "frame")}_f${console.frame}")
        }
        if (environmentVariable("BENCH_WATCH_HEX") == "1") {
            val bytes = HgssReader(memory, version).partyRaw().map { b -> b.joinToString("") { "%02x".fmt(it) } }
            bytes.forEachIndexed { i, h -> if (lastHex.getOrNull(i) != h) println("    hex $i: $h") }
            lastHex = bytes
        }
    }

    private var lastHex: List<String> = emptyList()

    /** `BENCH_WATCH_FIXTURE=<prefix>`: while watching, frames where a party slot is mid-rewrite are saved as fixtures. */
    private var tornFixtures = 0

    /**
     * True when the plain reading the flags announce is wrong: the game is rewriting the Pokémon (blocks or party data
     * caught encrypted / decrypted / torn, see Gen4Pokemon.decode).
     */
    private fun midRewrite(raw: ByteArray): Boolean {
        val mon = Gen4Pokemon
        val flags = Gen4RomBytes.u16(raw, 4)
        val checksum = Gen4RomBytes.u16(raw, 6)
        val box = raw.copyOfRange(8, 0x88).also { if (flags and 2 == 0) mon.crypt(it, 0, it.size, checksum.toLong()) }
        val boxOk = (0 until 0x40).sumOf { Gen4RomBytes.u16(box, 2 * it) } and 0xFFFF == checksum
        val naive = mon.decode(raw) { true } ?: return false
        return !boxOk || !dev.kotlinds.pokemonclient.games.hgss.HgssMonCheck.isPlausible(naive)
    }

    private fun saveTornFixture(memory: Memory) {
        val prefix = environmentVariable("BENCH_WATCH_FIXTURE") ?: return
        if (tornFixtures >= 6) return
        val torn = HgssReader(memory, romVersion).partyRaw().withIndex().filter { midRewrite(it.value) }.map { it.index }
            .filter { slot -> environmentVariable("BENCH_WATCH_FIXTURE_SLOT")?.let { it.toInt() == slot } ?: true }
        if (torn.isEmpty()) return
        val name = "${prefix}_${tornFixtures++}_f${console.frame}_slot${torn.joinToString("-")}"
        fixture(name)
    }

    private fun trace(frames: Int) {
        var last: String? = null
        repeat(frames) {
            scope.step(1)
            val line = describe()
            if (line != last) println("  [${console.frame}] $line")
            last = line
        }
    }

    /** Steps [frames] frames printing every change of the battle message (state) and the screen kind. */
    private fun msgTrace(frames: Int, mash: Boolean = false) {
        var last: String? = null
        repeat(frames) { i ->
            // mash: A pressed one frame out of four, like an impatient player (or a plan pressing through texts).
            scope.step(1, if (mash && i % 4 == 0) InputFrame.of(Button.A) else InputFrame.NONE)
            val state = game.state(scope.memory())
            val line = "${state.screen.kind} | ${state.battle?.message?.replace("\n", "/")} | shakes=${state.battle?.ballShakes}"
            if (line != last) println("  [${console.frame}] $line")
            last = line
        }
    }

    /**
     * Plays up to [turns] decisions of the current battle like an agent would (attack with [move] or the first move
     * with PP, keep battling, send the first Pokémon able to fight, keep the old moves, read on), with the app's
     * settle after each action; then compares the battle messages read on every frame with the recorder's.
     */
    private var truthSince = 0L

    /** The battle message of every frame since `truth:on` (null: no battle message), for `truth:dump`. */
    private val truthFrames = mutableListOf<String?>()

    private fun truthOn() {
        truth = mutableListOf()
        truthFrames.clear()
        truthSince = recorder.log.lastSeq
    }

    private fun truthCheck() {
        val seen = truth ?: return
        truth = null
        val recorded = recorder.log.since(truthSince).filterIsInstance<dev.kotlinds.pokemonclient.state.GameEvent.TextShown>()
            .filter { it.source == dev.kotlinds.pokemonclient.state.TextSource.BATTLE }.map { it.text }
        val missing = seen.filter { it !in recorded }
        println("  battle messages shown: ${seen.size}, recorded: ${recorded.size}, missing: ${missing.size}")
        missing.forEach { println("    MISSING: ${it.replace("\n", "/")}") }
    }

    private fun autoBattle(turns: Int, move: String?, healItems: List<Int> = listOf(FULL_RESTORE)) {
        if (truth == null) truthOn()
        var heals = 0
        var switched = false
        var healed = false
        repeat(turns) {
            val state = game.state(scope.memory())
            val screen = state.screen
            val json = when {
                state.battle == null && screen is Screen.Overworld -> return@repeat
                // Never two heals in a row: a foe that hits harder than the item heals would keep the battler healing forever.
                screen is Screen.BattleCommand && move == "move:best" && heals < MAX_HEALS && !healed && healItem(state, healItems) != null &&
                    state.battle?.battlers?.firstOrNull { it.ref == screen.actor }?.let { it.hp * 100 < it.maxHp * HEAL_BELOW_PERCENT } == true -> {
                    heals++
                    healed = true
                    val actor = state.battle?.battlers?.firstOrNull { it.ref == screen.actor }
                    """{"type":"use_item","item":"item:${healItem(state, healItems)}","target":"${actor?.mon}"}"""
                }
                screen is Screen.BattleCommand -> {
                    val actor = state.battle?.battlers?.firstOrNull { it.ref == screen.actor }
                    // `move:best` with nothing that hurts the foe (no PP left, immune), or a party member far better: it goes in
                    // (not twice in a row, so two Pokémon don't take turns forever).
                    if (move == "move:best" && !switched) {
                        val other = if (bestMove(state) == null) bestSwitch(state, null) else betterSwitch(state, TURN_SWITCH_FACTOR)
                        if (other != null) {
                            switched = true
                            run("""act:{"type":"switch","pokemon":"$other"}""")
                            return@repeat
                        }
                    }
                    switched = false
                    healed = false
                    val chosen = (if (move == "move:best") bestMove(state) else null)
                        ?: move?.takeIf { m -> actor?.moves?.any { "move:${it.move.id.value}" == m && it.pp > 0 } == true }
                        ?: actor?.moves?.firstOrNull { it.pp > 0 }?.let { "move:${it.move.id.value}" } ?: return@repeat
                    """{"type":"attack","move":"$chosen"}"""
                }
                // `move:best`: the foe sends another Pokémon; a party member that hits it much harder goes in.
                screen is Screen.ListMenu && screen.kind == dev.kotlinds.pokemonclient.state.MenuKind.BATTLE_SWITCH_OR_KEEP && move == "move:best" &&
                    betterSwitch(state) != null -> """{"type":"switch","pokemon":"${betterSwitch(state)}"}"""
                screen is Screen.ListMenu && screen.kind == dev.kotlinds.pokemonclient.state.MenuKind.BATTLE_SWITCH_OR_KEEP -> """{"type":"keep_battling"}"""
                screen is Screen.PartyGrid -> ((if (move == "move:best") bestSwitch(state, screen) else null)
                    ?: screen.entries.firstOrNull { it.selectable && it.id.startsWith("mon:") }?.id)?.let { """{"type":"switch","pokemon":"$it"}""" } ?: return@repeat
                screen is Screen.YesNo && screen.learning != null -> """{"type":"learn_move"}"""
                screen is Screen.YesNo && screen.entries.any { it.id == "option:next" } -> """{"type":"choose","entry":"option:next"}"""
                else -> """{"type":"advance_dialogue"}"""
            }
            run("act:$json")
        }
        truthCheck()
    }

    /** The first of [items] still in the bag. */
    private fun healItem(state: dev.kotlinds.pokemonclient.state.GameState, items: List<Int>): Int? =
        items.firstOrNull { id -> state.bag.orEmpty().any { pocket -> pocket.items.any { it.item.id.value == id && it.quantity > 0 } } }

    /** `move:best` uses its healing item on the acting Pokémon below this share of its HP, at most [MAX_HEALS] times a battle. */
    private val HEAL_BELOW_PERCENT = 45
    private val MAX_HEALS = 8
    private val FULL_RESTORE = 23
    private val REVIVE = 28

    /**
     * `move:best`: how hard [known] of [mon] would hit the foes out now: power x type effectiveness x accuracy x the
     * attacking stat (Attack or Sp. Atk) x 1.5 for the same type; 0 for a status move or one without PP.
     */
    private fun moveScore(mon: dev.kotlinds.pokemonclient.state.PartyMon?, known: dev.kotlinds.pokemonclient.state.KnownMove, matchups: List<dev.kotlinds.pokemonclient.data.MoveMatchup>): Double {
        val info = game.data?.move(known.move.id) ?: return 0.0
        if (known.pp <= 0 || info.power == 0) return 0.0
        val multiplier = matchups.filter { it.move == known.move.name }.maxOfOrNull { it.multiplier } ?: return 0.0
        val stat = mon?.stats?.get(
            if (info.category == dev.kotlinds.pokemonclient.data.MoveCategory.PHYSICAL) dev.kotlinds.pokemonclient.state.Stat.ATTACK else dev.kotlinds.pokemonclient.state.Stat.SP_ATTACK,
        ) ?: 100
        val stab = if (mon?.types?.any { it.equals(info.type.name, ignoreCase = true) } == true) 1.5 else 1.0
        return info.power * multiplier * (if (info.accuracy == 0) 100 else info.accuracy) / 100.0 * stat * stab
    }

    /** `move:best`: the acting battler, its party Pokémon and its best score against the foes out now. */
    private fun actorScore(state: dev.kotlinds.pokemonclient.state.GameState): Triple<dev.kotlinds.pokemonclient.state.BattlerState, dev.kotlinds.pokemonclient.state.PartyMon?, Double>? {
        val battle = state.battle ?: return null
        val data = game.data ?: return null
        val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: dev.kotlinds.pokemonclient.state.BattlerRef.PLAYER_LEFT) } ?: return null
        val mon = state.party.firstOrNull { it.id == actor.mon }
        val matchups = dev.kotlinds.pokemonclient.data.Matchups.estimate(battle, data)
        return Triple(actor, mon, actor.moves.maxOfOrNull { moveScore(mon, it, matchups) } ?: 0.0)
    }

    /** `move:best`: the best party member to send in and its score (not battling, not fainted). */
    private fun bestOfParty(state: dev.kotlinds.pokemonclient.state.GameState, selectable: Set<String>?): Pair<String, Double>? {
        val battle = state.battle ?: return null
        val data = game.data ?: return null
        return dev.kotlinds.pokemonclient.data.Matchups.party(battle, state.party, data)
            .filter { selectable == null || it.mon.id.toString() in selectable }
            .map { p -> p.mon.id.toString() to (p.mon.moves.maxOfOrNull { moveScore(p.mon, it, p.matchups) } ?: 0.0) }
            .maxByOrNull { it.second }
    }

    /** `move:best`: a party member whose best move scores [SWITCH_FACTOR] times the battler's, if any. */
    private fun betterSwitch(state: dev.kotlinds.pokemonclient.state.GameState, factor: Double = SWITCH_FACTOR): String? {
        val current = actorScore(state)?.third ?: return null
        val best = bestOfParty(state, null) ?: return null
        return best.first.takeIf { best.second > current * factor }
    }

    private val SWITCH_FACTOR = 1.5

    /** At its own turn, the battler is switched out only for a party member this many times better. */
    private val TURN_SWITCH_FACTOR = 2.5

    /** `move:best`: the acting Pokémon's move with the best [moveScore]. */
    private fun bestMove(state: dev.kotlinds.pokemonclient.state.GameState): String? {
        val battle = state.battle ?: return null
        val data = game.data ?: return null
        val (actor, mon, _) = actorScore(state) ?: return null
        val matchups = dev.kotlinds.pokemonclient.data.Matchups.estimate(battle, data)
        return actor.moves.filter { moveScore(mon, it, matchups) > 0 }.maxByOrNull { moveScore(mon, it, matchups) }?.let { "move:${it.move.id.value}" }
    }

    /**
     * `move:best`: the party member to send in, the one whose best move hits the foe hardest: among those [grid] offers
     * when one must replace a fainted Pokémon, else (a switch by choice) among the others that can hurt the foe at all.
     */
    private fun bestSwitch(state: dev.kotlinds.pokemonclient.state.GameState, grid: Screen.PartyGrid?): String? {
        val selectable = grid?.entries?.filter { it.selectable && it.id.startsWith("mon:") }?.map { it.id }?.toSet()
        val best = bestOfParty(state, selectable) ?: return null
        return best.first.takeIf { grid != null || best.second > 0 }
    }

    private fun describe(): String {
        val memory = scope.memory()
        val fishing = hgssVersion?.let { HgssFishing.state(HgssMemory(memory, it)) }
        val state = game.state(memory)
        val position = state.field?.let { " at ${it.x},${it.y}${if (it.moving) " moving" else ""} ${it.movement.name.lowercase()}" } ?: ""
        return describe(state.screen, oneLine = true) + position + (fishing?.let { " fishing=$it" } ?: "")
    }

    /**
     * Fishing "watch": steps up to [frames] frames, presses A for one frame on the first frame of a bite, and
     * prints every change of the fishing probe.
     */
    private fun fish(frames: Int) {
        var last: String? = null
        repeat(frames) {
            val memory = scope.memory()
            val state = HgssFishing.state(HgssMemory(memory, romVersion))
            val line = "$state"
            if (line != last) println("  [${console.frame}] fishing=$line screen=${describe(game.state(memory).screen, oneLine = true)}")
            last = line
            scope.step(1, if (state is FishingState.Bite) InputFrame.of(Button.A) else InputFrame.NONE)
        }
    }

    /** Walks [steps] tiles towards [button]: holds it until the player's tile changes, then waits until it stands still. */
    private fun steps(button: Button, steps: Int) {
        // Any game: the common model's position.
        fun position() = game.state(scope.memory()).field?.let { Triple(it.x, it.y, it.moving) }
        repeat(steps) {
            val start = position()
            scope.stepUntil(40, InputFrame.of(button)) { position()?.let { (x, z, _) -> x != start?.first || z != start.second } == true }
            scope.stepUntil(40) { position()?.third == false }
        }
        println("  at ${position()}")
    }

    /**
     * Taps each button and checks the decoded topology: the cursor must land where `topology.next` predicted
     * (staying put when it predicted null). Prints OK / MISMATCH per tap.
     */
    private fun walk(buttons: List<Button>) {
        var ok = 0
        for (button in buttons) {
            val before = game.state(scope.memory()).screen as? Screen.Selectable ?: error("not a selectable screen")
            val from = (before.cursor as? dev.kotlinds.pokemonclient.state.Cursor.At)?.index ?: error("cursor hidden")
            val predicted = before.topology.next(from, button) ?: from
            scope.tap(button)
            scope.step(6)
            val after = game.state(scope.memory()).screen as? Screen.Selectable
            val got = (after?.cursor as? dev.kotlinds.pokemonclient.state.Cursor.At)?.index
            val same = after != null && after::class == before::class
            if (same && got == predicted) ok++
            println("  ${if (same && got == predicted) "OK" else "MISMATCH"} $button: $from -> $got (predicted $predicted)${if (!same) " screen ${after?.let { it::class.simpleName }}" else ""}")
        }
        println("  walk: $ok/${buttons.size} OK")
    }

    /**
     * The one description of a screen the bench prints: its kind and, for a selectable screen, what it is about (list
     * kind, bag pocket, party purpose, question...), the cursor, the cancel entry and every entry (id, label, `x` when
     * not selectable, touch point) with the D-pad moves of the topology. [oneLine]: the entries on the same line (for
     * traces, one line per change).
     */
    private fun describe(screen: Screen, oneLine: Boolean = false): String = buildString {
        append(if (oneLine) "" else "  ").append(screen::class.simpleName)
        if (screen !is Screen.Selectable) {
            append(" ").append(screen.toString().replace('\n', ' '))
            return@buildString
        }
        when (screen) {
            is Screen.ListMenu -> append(" kind=${screen.kind}")
            is Screen.Bag -> append(" pocket=${screen.pocket} page=${screen.page}/${screen.pages}")
            is Screen.PartyGrid -> append(" purpose=${screen.purpose}")
            is Screen.ContextMenu -> append(" owner=${screen.owner}")
            is Screen.YesNo -> append(" question=${screen.question}")
            else -> {}
        }
        append(" cursor=${screen.cursor} cancel=${screen.cancel}")
        val buttons = listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT, Button.L, Button.R)
        screen.entries.forEachIndexed { i, e ->
            val moves = buttons.mapNotNull { b -> screen.topology.next(i, b)?.let { "${b.name[0]}${if (b == Button.L || b == Button.R) "b" else ""}$it" } }
            append(if (oneLine) " | " else "\n    ")
            append("$i ${e.id} \"${e.label.replace('\n', ' ')}\"${if (!e.selectable) " (x)" else ""}${e.touch?.let { " touch=${it.x},${it.y}" } ?: ""} ${moves.joinToString(" ")}")
        }
    }

    /** Presses A from power-on until the player can walk (title screen, main menu CONTINUE, recap). */
    private fun boot(maxFrames: Int) {
        scope.step(120)
        while (scope.framesUsed < maxFrames) {
            val screen = game.state(scope.memory()).screen
            if (screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT) {
                println("  overworld at frame ${console.frame}")
                return
            }
            scope.tap(Button.A)
            scope.step(20)
        }
        error("never reached the overworld")
    }

    /**
     * `trip`: runs [goToJson] (a `go_to`) until it is done, handling what interrupts it like a simple agent: a wild
     * battle is fled ([policy] `flee`) or won with the first move that has PP left (`fight`), a trainer battle is won
     * the same way, texts and calls are read through. Every action settles like `act`. Prints the total frames (the
     * control back at the destination) and the battles met, to compare the same trip under different route weights.
     */
    private fun trip(policy: String, goToJson: String) {
        require(policy == "flee" || policy == "fight") { "trip:<flee|fight>:<go_to json>" }
        val goTo = registry.parse(Json.parseToJsonElement(goToJson).jsonObject, ActionMode.ASSISTED).getOrThrow()
        val start = console.frame
        var wild = 0
        var trainers = 0
        var inBattle = false
        var stuck = 0
        fun execute(json: String): dev.kotlinds.pokemonclient.actions.ActionOutcome? {
            val action = registry.parse(Json.parseToJsonElement(json).jsonObject, ActionMode.ASSISTED).getOrNull() ?: return null
            return registry.executeAndSettle(action, scope, game, options.actionSettings)
        }
        repeat(MAX_TRIP_ACTIONS) {
            val state = game.state(scope.memory())
            val battle = state.battle
            if (battle != null && !inBattle) if (battle.kind == dev.kotlinds.pokemonclient.state.BattleKind.WILD) wild++ else trainers++
            inBattle = battle != null
            val screen = state.screen
            when {
                screen is Screen.Overworld && battle == null && screen.incomingCall == null -> {
                    val outcome = registry.executeAndSettle(goTo, scope, game, options.actionSettings)
                    if (outcome is dev.kotlinds.pokemonclient.actions.ActionOutcome.Done) {
                        println("  trip done: ${console.frame - start} frames, $wild wild battle(s), $trainers trainer battle(s) (${outcome.detail ?: ""})")
                        return
                    }
                    // A refusal with nothing else going on (no battle, no text): the trip can't go on.
                    val after = game.state(scope.memory())
                    if (after.screen is Screen.Overworld && after.battle == null && (after.screen as Screen.Overworld).incomingCall == null) {
                        println("  trip FAILED after ${console.frame - start} frames: $outcome")
                        return
                    }
                }
                screen is Screen.BattleCommand && battle != null -> {
                    val flee = battle.kind == dev.kotlinds.pokemonclient.state.BattleKind.WILD && policy == "flee"
                    val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: dev.kotlinds.pokemonclient.state.BattlerRef.PLAYER_LEFT) }
                    val move = actor?.moves?.firstOrNull { it.pp > 0 }?.move?.id?.value
                    val json = if (flee || move == null) """{"type":"run"}""" else """{"type":"attack","move":"move:$move"}"""
                    execute(json) ?: execute("""{"type":"run"}""")
                }
                else -> {
                    // A "keep battling?" question, a move to learn (kept out), texts and calls: whatever moves things on
                    // (the question first: reading texts does nothing once it is up).
                    val done = listOf("""{"type":"keep_battling"}""", """{"type":"learn_move"}""", """{"type":"advance_dialogue"}""")
                        .firstNotNullOfOrNull { json -> execute(json)?.takeIf { it is dev.kotlinds.pokemonclient.actions.ActionOutcome.Done } }
                    if (done == null) {
                        // A screen this simple agent can't handle (a fainted Pokémon to replace...): A, a few times.
                        if (++stuck > MAX_TRIP_STUCK) {
                            println("  trip NOT done (stuck on ${screen.kind}): ${console.frame - start} frames, $wild wild, $trainers trainer battle(s)")
                            return
                        }
                        scope.tap(Button.A)
                        scope.step(20)
                    } else stuck = 0
                }
            }
        }
        println("  trip NOT done after $MAX_TRIP_ACTIONS actions: ${console.frame - start} frames, $wild wild, $trainers trainer battle(s); ${game.state(scope.memory()).screen}")
    }

    private companion object {
        /** Bound of a `trip`: actions before giving up. */
        const val MAX_TRIP_ACTIONS = 200

        /** Presses of A on a screen a `trip` can't handle before giving up. */
        const val MAX_TRIP_STUCK = 10

        /** Bench commands that read HeartGold / SoulSilver structures directly ([HgssReader]...). */
        val HGSS_ONLY = setOf("raw", "rawmon", "box", "where", "watch", "fish", "world")

        /** About 5 s of game time: how often a long action's progress is printed. */
        const val PROGRESS_PRINT_FRAMES = 300L
    }

    private fun ram(): ByteArray =
        ByteArray(console.memorySize(MemoryRegion.MAIN_RAM)).also { console.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }

    /** Decodes the current frame through a recording memory and keeps only the bytes read. */
    private fun fixture(name: String) {
        val recording = RecordingMemory(RamMemory(ram()))
        game.state(recording)
        writeFixture(name, recording)
    }

    private fun writeFixture(name: String, recording: RecordingMemory) {
        val file = Path(out, "$name.ram.sparse.gz")
        // "SPRM", then per range its start and size (big-endian 32 bits) and its bytes; gzipped.
        val sparse = Buffer().apply {
            write("SPRM".encodeToByteArray())
            recording.ranges().forEach { (start, bytes) ->
                writeInt(start.toInt())
                writeInt(bytes.size)
                write(bytes)
            }
        }.readByteArray()
        Files.writeBytes(file, gzip(sparse))
        println("  fixture $file (${recording.ranges().sumOf { it.second.size }} bytes)")
    }

    private val romImage: NdsRom by lazy { NdsRom.parse(Files.readBytes(romPath)) }
    /** The HeartGold / SoulSilver build of the ROM (detected from its game code), null for another game. */
    private val hgssVersion: HgssVersion? by lazy { HgssVersion.forGameCode(romImage.gameCode) }

    /** The HGSS build, for the commands that read HGSS RAM or ROM structures; fails clearly on another game. */
    private val romVersion: HgssVersion
        get() = hgssVersion ?: error("this bench command reads HeartGold / SoulSilver structures, not ${game.name} (${romImage.gameCode})")
    /**
     * The game's own world (the common Gen 4 decoder: headers, matrices, land data, events), for `world`; another
     * engine fails clearly.
     */
    private val world: Gen4WorldSource<*>
        get() = game.world as? Gen4WorldSource<*> ?: error("`world` checks the Gen 4 world decoder: ${game.name} has no Gen 4 world (no ROM?)")

    /** Checks the ROM world decoder against the map loaded in RAM (every tile of every loaded block, zone events). */
    private fun world(name: String) {
        val recording = RecordingMemory(RamMemory(ram()))
        val terrain = HgssLoadedMap(recording, romVersion)
        val zone = terrain.zoneId ?: error("not in the field")
        val header = world.header(zone)!!
        val matrix = world.matrix(header.matrixId)!!
        val area = world.areaOf(zone)!!
        var compared = 0
        var mismatches = 0
        for (block in terrain.loadedBlocks) {
            val bx = block % terrain.matrixWidth
            val bz = block / terrain.matrixWidth
            val land = world.landData(matrix.landAt(bx, bz)) ?: continue
            for (lz in 0 until 32) for (lx in 0 until 32) {
                val x = bx * 32 + lx
                val z = bz * 32 + lz
                val live = terrain.attribute(x, z) ?: continue
                compared++
                val decoded = land.attribute(lx, lz)
                val tile = area.tile(x, z)
                if (live != decoded || tile?.blocked != (live and 0x8000 != 0)) {
                    if (mismatches++ < 10) println("  MISMATCH ($x,$z) ram=0x${live.toString(16)} rom=0x${decoded.toString(16)} tile=$tile")
                }
            }
        }
        val px = terrain.playerX!!
        val pz = terrain.playerZ!!
        println("  zone $zone ${game.mapName(zone)} matrix ${matrix.id} blocks ${terrain.loadedBlocks} compared $compared tiles, $mismatches mismatches")
        println("  player ($px,$pz) height ${terrain.playerHeight} tile ${area.tile(px, pz)} zoneAt ${area.zoneAt(px, pz)} altitude ${matrix.altitudeAt(px / 32, pz / 32)}")
        val live = terrain.events()
        val rom = world.events(zone)
        println("  events: ${if (live == rom) "RAM = ROM" else "DIFFER\n    ram=$live\n    rom=$rom"} (${rom?.bgs?.size} bgs, ${rom?.objects?.size} objects, ${rom?.warps?.size} warps, ${rom?.coords?.size} coords)")
        if (name.isNotEmpty()) writeFixture(name, recording)
    }
}

/** [Memory] that remembers which addresses were read, to store only those in a sparse fixture. */
private class RecordingMemory(private val memory: Memory) : Memory {
    private val touched = HashSet<Long>()

    private fun touch(addr: Long, size: Int) {
        for (i in 0 until size) touched += addr + i
    }

    override fun read8(addr: Long) = memory.read8(addr).also { touch(addr, 1) }
    override fun read16(addr: Long) = memory.read16(addr).also { touch(addr, 2) }
    override fun read32(addr: Long) = memory.read32(addr).also { touch(addr, 4) }
    override fun readBytes(addr: Long, size: Int) = memory.readBytes(addr, size).also { touch(addr, size) }

    /** Contiguous runs of touched bytes, with their values. */
    fun ranges(): List<Pair<Long, ByteArray>> {
        val result = mutableListOf<Pair<Long, ByteArray>>()
        var start = -1L
        var previous = -2L
        fun flush() {
            if (start >= 0) result += start to memory.readBytes(start, (previous - start + 1).toInt())
        }
        for (addr in touched.sorted()) {
            if (addr != previous + 1) {
                flush()
                start = addr
            }
            previous = addr
        }
        flush()
        return result.filter { (addr, _) -> addr in 0x02000000L..0x023FFFFFL }
    }
}
