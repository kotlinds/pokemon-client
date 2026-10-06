# pokemon-client

Kotlin Multiplatform client for Pokémon games: reads a game from its RAM (and ROM) into one common, typed model and
plays it through typed, self-checking actions. Used by [ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon)
to let AIs play Pokémon HeartGold / SoulSilver and Platinum.

[![License](https://img.shields.io/github/license/kotlinds/pokemon-client)](LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/dev.kotlinds/pokemon-client)](https://klibs.io/project/kotlinds/pokemon-client)
[![Issues](https://img.shields.io/github/issues/kotlinds/pokemon-client)]()
[![Pull Requests](https://img.shields.io/github/issues-pr/kotlinds/pokemon-client)]()

## Modules

- **`pokemon-client`** (Kotlin Multiplatform, as much as possible in `commonMain`) knows Pokémon, not emulators. A game
  reads the RAM into one common, typed model: `GameState` with a sealed `Screen` (every menu has its entries with
  **stable, language-independent ids** like `option:yes`, `mon:8dd175d1.76f3a6fb`, `move:85`, its cursor and the exact
  D-pad topology). Actions are typed (`GameAction`) and carried out by **plans** that never press blindly: the
  `Navigator` reads the cursor, moves it one verified tap at a time and confirms only on the target (3 corrections at
  most, then an explicit error). Movement uses the maps read from the **ROM** with
  [kotlinds](https://github.com/kotlinds/kotlinds) (tiles, heights, warps, events) with the live people and the game's
  script variables on top. Nothing is ever written to the game's RAM: everything goes through buttons and the touch
  screen, like a player. The emulator is reached only through `ConsolePort`.
- **`pokemon-client-libretro`** (JVM) runs the client on a libretro core through
  [libretro-kmp](https://github.com/kotlinds/libretro-kmp): `LibretroConsole` (the `ConsolePort` adapter), the known
  cores (DeSmuME, melonDS; pinned downloads checked by SHA-256), save formats, the sound state splicing used for music
  during pauses, and a headless **bench**.

```
 ┌──────────────── pokemon-client (dev.kotlinds.pokemonclient) ───────────┐
 │ state: GameState, sealed Screen (entries, cursor, topology), events    │
 │ runtime: ActionScope (self-checking taps), Recorder                     │
 │ actions: Navigator (verified cursor moves), typed GameActions, plans,  │
 │          ActionRegistry (schema, availability, typed errors)            │
 │ world: Area, Pathfinder (levels, ledges, surf, triggers)  view: MapView │
 │ data: GameData, Lookup, KnowledgeLevel                                  │
 │ games: gen4 (shared Gen 4 engine) · hgss (HeartGold/SoulSilver)         │
 │        · platinum                                                       │
 └───────────────┬─────────────────────────────────────────────────────────┘
                 │ ConsolePort (step, read RAM, frames, save states)
 ┌───────────────▼──── pokemon-client-libretro ────────────────────────────┐
 │ LibretroConsole ── libretro-kmp ── DeSmuME / melonDS core · bench       │
 └─────────────────────────────────────────────────────────────────────────┘
```

## Installation

```kotlin
dependencies {
    implementation("dev.kotlinds:pokemon-client:0.1.0")
    implementation("dev.kotlinds:pokemon-client-libretro:0.1.0") // to run it on a libretro core (JVM)
}
```

## Development

Between releases the version is a `-SNAPSHOT`: publish it locally with `./gradlew publishToMavenLocal` and consume it
from `mavenLocal()` (as ai-plays-pokemon does while developing).

Both modules are Kotlin Multiplatform with the JVM target only for now, and written to take more targets: the code and
the tests live in `commonMain` / `commonTest`, and `jvmMain` / `jvmTest` only hold the `actual`s of what the platform
provides (declared in `PlatformServices.kt`, `BundledResources.kt` and the tests' `TestPlatform.kt`), plus one test
that runs real threads. Files go through [kotlinx-io](https://github.com/Kotlin/kotlinx-io).

Tests: `./gradlew jvmTest`. The tests reading a ROM run only when its path is given (they are skipped
otherwise): `POKEMON_ROM` (HeartGold US) and `PLATINUM_ROM` (Platinum US). Coverage with `./gradlew koverHtmlReport`.

The headless **bench** (`runBench` in `dev.kotlinds.pokemonclient.libretro.bench`, see its KDoc; common code, started
by `BenchMain.kt` on the JVM) runs commands and actions on a ROM without any app (`BENCH_WINDOW=1` shows it live,
muted, where the platform has a window) and writes the sparse RAM fixtures of the unit tests:

```bash
POKEMON_ROM=/path/to/rom.nds EMULATOR_CORE=desmume ./gradlew -q :pokemon-client-libretro:bench \
  "-PbenchArgs=<data dir>|<out dir>|load:my.state|step:1|act:{\"type\":\"heal\"}|shot:after"
```

## Adding a game

See [docs/adding-a-game.md](docs/adding-a-game.md): implement `PokemonGame` (RAM → `GameState`, screen decoders,
optionally `world` and `data` from the ROM) and register its ROM code in `PokemonGames`; the plans, the navigator and
the action registry work unchanged.
