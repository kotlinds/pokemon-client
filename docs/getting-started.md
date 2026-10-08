# Getting started

What to do once `pokemon-client` and `pokemon-client-libretro` are in your dependencies (see the
[README](../README.md#installation)): boot a ROM on a libretro core, read the game as typed state, and play it with
typed actions, from Kotlin or from JSON (as an MCP server or an LLM would).

Every snippet of this page is the code of
[`GettingStartedTest`](../pokemon-client-libretro/src/commonTest/kotlin/dev/kotlinds/pokemonclient/libretro/GettingStartedTest.kt),
section by section (`// doc:` markers): it compiles with every build, runs on HeartGold (USA) when `POKEMON_ROM` and
`LIBRETRO_CORES` (a directory holding the DeSmuME core, e.g. the bench's `cores/`) are set, and checks on every run
that this page shows its code verbatim. Change the test first, then copy its blocks here.

## 1. Boot a ROM

You need a Nintendo DS ROM of a supported game (HeartGold / SoulSilver, Platinum: see
[`PokemonGames`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/PokemonGames.kt)) and a libretro
core. The known cores are [`LibretroCoreSpec`](../pokemon-client-libretro/src/commonMain/kotlin/dev/kotlinds/pokemonclient/libretro/LibretroCoreSpec.kt):
`DESMUME` (the default for `.nds`, and the one that can continue an existing in-game save) and `MELONDS`. You don't
download them yourself: the console looks for the core in `<data directory>/cores/` and, when it's missing, downloads
it from the libretro buildbot and checks its pinned SHA-256 (on the pinned platforms: an unknown build is refused,
not run).

```kotlin
val game: PokemonGame = PokemonGames.detect(SystemFileSystem.source(rom).buffered().use { it.readByteArray() })
    ?: error("Unsupported ROM: $rom")
val console = LibretroConsole(
    spec = LibretroCoreSpec.DESMUME, // or LibretroCoreSpec.forRom(rom, preferred = "melonds")
    rom = rom,
    dataDirectory = dataDirectory, // holds cores/, saves/ (<rom>.sav), system/
    onVideo = { frame -> /* draw frame.pixels (ARGB, both screens stacked) */ },
    onAudio = { samples, frames -> /* play interleaved stereo samples */ },
)
```

`rom` and `dataDirectory` are `kotlinx.io.files.Path`s. `PokemonGames.detect` reads the game code from the ROM and
returns the game, with its maps and data read from the ROM (null for an unsupported ROM). Saves:

- **In-game save**: put it in `<data directory>/saves/<rom name>.sav` (raw save memory, any emulator's). The console
  converts it to the core's own format before loading ([`SaveFormat`](../pokemon-client-libretro/src/commonMain/kotlin/dev/kotlinds/pokemonclient/libretro/SaveFormat.kt))
  and writes it back on `close()`. No save: the game starts a new game.
- **Save states**: `console.saveState()` / `console.loadState(bytes)` (a state is tied to its core).

Close the console when done (`console.use { ... }`): it unloads the core, which writes the in-game save.

## 2. Run frames: who advances time

Nothing moves unless someone calls `step`: [`ConsolePort`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/console/ConsolePort.kt)
(the library's only view of an emulator, implemented by `LibretroConsole`) is synchronous and **not thread-safe**. Own
it from one thread (the "console thread") and do everything there: stepping, reading, actions.
[`ActionScope`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/runtime/ActionScope.kt) is the
handle actions play through: it steps frames, makes self-checking presses and reads the RAM.

```kotlin
val recorder = Recorder(game) // turns what the game shows into events (texts, level ups...)
lateinit var scope: ActionScope
scope = ActionScope(console, game.inputProbe, onFrame = { recorder.onFrame(console.frame) { scope.memory() } })
scope.step(600) // nothing moves unless someone steps: ~10 s of game
```

`step` runs as fast as the core can: pace the frames yourself in `onFrame` if a human watches (~60 fps). `ActionScope`
also takes `interruption` (checked before every frame: return `Interruption.HUMAN` / `CANCELLED` to stop an action, it
then fails with `ActionError.Interrupted`) and `onProgress` (how far a long `go_to` has got). The `Recorder` is
optional: it feeds the messages and events of the agent's view (section 6).

An app that also lets a human play runs the free game loop on the console thread and gives an action exclusive use of
the console for its duration (a "lease"): see `ConsoleHost` in [ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon).

## 3. Read the typed state

`game.state(memory)` decodes the RAM into [`GameState`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/state/GameState.kt),
the same model for every game: `screen`, `party`, `bag`, `player`, `field` (map, position, people...), `battle`,
`story`, `storage`, `pokedex`... A null or empty field means "not read" (the game doesn't decode it yet), never
"none". The [`Screen`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/state/Screen.kt) is a sealed
hierarchy: menus are `Screen.Selectable` with entries identified by stable, language-independent ids (`option:yes`,
`move:85`, `mon:8dd175d1.76f3a6fb`), their cursor and D-pad topology; `awaiting` says whether the game waits for input.

```kotlin
val state: GameState = game.state(scope.memory())
when (val screen = state.screen) {
    is Screen.Intro -> println("intro: ${screen.detail}, goes on with ${screen.goesOnWith}")
    is Screen.Selectable -> println("menu: ${screen.entries.map { it.id }}, cursor ${screen.cursor}")
    is Screen.Overworld -> println("walking on ${state.field?.mapName}")
    else -> println("screen ${screen.awaiting}")
}
println("party: ${state.party.map { "${it.species.name} L${it.level}" }}")
```

`scope.memory()` reuses one buffer: read what you need before stepping again.

## 4. List and execute actions

Actions are a closed, typed set ([`GameAction`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/actions/GameAction.kt)),
the same for every game. [`ActionRegistry`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/actions/ActionRegistry.kt)
is their single entry point: what is possible now, the JSON schema (`jsonSchema(mode)`), parsing and execution, with
the game's own recipes. `ActionMode.ASSISTED` gives every action, `PURE` only raw buttons and touches.

```kotlin
val registry = ActionRegistry.of()
val available = registry.available(state, ActionMode.ASSISTED, game)
available.forEach { println("${it.name} ${it.choices}") }
registry.unavailable(state, ActionMode.ASSISTED, game).forEach { println("${it.name}: ${it.reason} ${it.detail}") }
```

`available` lists what can run now with its valid parameter values (`choices`), `unavailable` what exists but can't
run now and why (a typed `UnavailableReason`), `enumerate` every concrete action by key (for models that pick from a
list). Execution checks the same availability first, so an action is never listed but refused:

```kotlin
when (val outcome = registry.executeAndSettle(GameAction.Wait(frames = 60), scope, game)) {
    is ActionOutcome.Done -> println("done: ${outcome.detail}")
    is ActionOutcome.Failed -> println("failed: ${outcome.error.code} ${outcome.error.message}")
}
val refused = registry.execute(GameAction.Run, scope, game) // fleeing outside a battle
val refusal = (refused as ActionOutcome.Failed).error
if (refusal is ActionError.Unavailable) println("${refusal.reason}: ${refusal.detail}") // WRONG_SCREEN: ...
```

`execute` runs the action; `executeAndSettle` then lets the game run (frames only, never a button) until it waits for
input again, within one agent step's budget: what every host does, so the next read is the screen the action led to.
Actions never press blindly: every press is read back, a wrong screen or cursor is retried at most 3 times, then the
action fails with a typed [`ActionError`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/actions/ActionError.kt)
(`code` is stable, `message` is one sentence for the agent). A refusal presses nothing. Pass `ActionSettings` (or
`AgentOptions.actionSettings`) to `execute` for what the recipes may do by themselves (solve movement puzzles, use
walkthrough knowledge).

### From JSON

An MCP server or an LLM sends `{"type": ..., ...}`. Parse it once at the boundary: unknown types, parameters or values
are refused with a typed error naming what to use instead, never guessed.

```kotlin
val wire = Json.parseToJsonElement("""{"type": "press", "button": "start"}""").jsonObject
val action: GameAction = registry.parse(wire, ActionMode.ASSISTED).getOrElse { failure ->
    val parseError = (failure as ActionException).error // INVALID_PARAM: unknown type / parameter / value
    error("${parseError.code}: ${parseError.message}")
}
val pressed = registry.executeAndSettle(action, scope, game)
val typo = registry.parse(Json.parseToJsonElement("""{"type": "press", "buton": "a"}""").jsonObject, ActionMode.ASSISTED)
println(typo.exceptionOrNull()?.message) // Unknown parameter `buton` for press: use `button` (The button)
```

### Chains

An agent may send an action and the steps to do after it (the MCP's `then`).
[`ChainRunner`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/actions/ChainRunner.kt) runs them
like a player would (item uses merged into one bag session...) and stops with a typed `ChainStop` when the steps left
became pointless (the opponent changed, a Pokémon fainted, nothing happened for 20 s...): they come back as not done.

```kotlin
val chain = runBlocking {
    ChainRunner.forAgent(
        recorder,
        observe = { game.state(scope.memory()) },
        execute = { step, _ -> registry.executeAndSettle(step, scope, game) },
        settle = { ActionRegistry.settleBetweenSteps(scope, game) },
    ).run(listOf(GameAction.Wait(frames = 30), GameAction.Wait(frames = 30)))
}
println("performed ${chain.performed}, failed ${chain.failed}, stopped ${chain.stop?.code}, not done ${chain.skipped.map { it.key }}")
```

## 5. Knowledge levels

[`KnowledgeLevel`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/data/Lookup.kt) sets how much an
agent may know beyond the screen: `NONE` (only what the game shows), `POKEDEX` (species and move sheets, type chart,
estimated effectiveness), `POKEDEX_PLUS_WALKTHROUGH` (trainers' teams, encounters, story goals, hidden items). It
changes what is **shown**, never what is computed; what the player sees on screen is shown at every level.

## 6. The agent's view (JSON)

[`AgentView`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/view/AgentView.kt) assembles what an
agent reads, the same way for every host: the messages and events since its last look, the state, battle estimates,
the text map around the player, the story (with a walkthrough) and the actions possible now. Keep one per agent (it
remembers what the agent has seen); `AgentOptions` holds the agent's mode, knowledge level and settings, so the view and
the actions always agree.

```kotlin
val view = AgentView(game, registry) // one per agent: it remembers what the agent has seen
val feed = EventFeed(recorder.log) // the events the agent hasn't been given yet
val json = view.describe(
    game.state(scope.memory()),
    feed.take().events,
    AgentOptions(knowledge = KnowledgeLevel.POKEDEX),
    AgentView.Detail.STANDARD,
)
println(json) // {"screen": ..., "actions": [{"type": "wait", ...}, ...], ...}
```

`Detail.COMPACT` is the answer after an action (what the agent already has is left out), `FULL` adds the PC boxes,
the options and each action's description.

## 7. Lookups

[`Lookup`](../pokemon-client/src/commonMain/kotlin/dev/kotlinds/pokemonclient/data/Lookup.kt) answers questions on the
game's data read from the ROM (`game.data`): species, moves, items, learnsets, TMs, types, wild encounters (give it an
`EncounterContext`), within a knowledge level. Ids are the typed ones (`species:25`, `move:85`, `item:17`, `tm01`,
`type:fire`); names are accepted too.

```kotlin
val data = game.data ?: error("no game data for ${game.name}")
val pikachu = Lookup(data, KnowledgeLevel.POKEDEX).lookup(LookupKind.SPECIES, "species:25")
val hidden = Lookup(data, KnowledgeLevel.NONE).lookup(LookupKind.SPECIES, "Pikachu")
println(pikachu.getOrThrow()) // types, base stats, abilities, evolutions...
println(hidden.exceptionOrNull()?.message) // SPECIES needs the knowledge level POKEDEX
```

## Where to go next

- **The bench** (`runBench`, see the [README](../README.md#development)): run commands and JSON actions on a ROM
  without any app (`act:`, `chain:`, `view`, `actions`, save states, screenshots). Its code is another complete
  example of the wiring above.
- **[ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon)**: the full application (`ConsoleHost` for the
  console thread and the human / agent leases, `GameSession` for the MCP server and its own LLM loop).
- **[Adding a game](adding-a-game.md)**: make another game playable, in this library or in your own project
  ("Writing a game outside the library").
- **[CONTRIBUTING.md](../CONTRIBUTING.md)**: the rules every change follows.
