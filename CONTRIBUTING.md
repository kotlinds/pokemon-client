# Contributing to pokemon-client

These are the rules of the project. They apply to every change, whoever (or whatever agent) writes it, and they are
checked in review. When a rule and a shortcut disagree, the rule wins.

## 1. The same contract for every game (the most important rule)

HeartGold / SoulSilver, Platinum and every game added later expose **exactly the same contract**, through the library
API and through any client built on it (the ai-plays-pokemon MCP server, an overlay, a web page...):

- the same actions: names, parameters, ids, typed errors;
- the same state description: same data classes, same sealed hierarchies, same meaning for each field.

A client written for one game must work with every game with zero game-specific code. So:

- never add a game-specific field, screen kind, action or JSON shape to the common model, and never expose raw
  per-game data;
- a game-specific concept is either mapped onto the common one, or added to the common model **for all games**, with
  an explicit "absent / unknown" value for the games that don't have it (yet);
- game differences live in `games/<game>` (addresses, decoders, data tables), and what the Gen 4 engine shares lives
  in `games/gen4`.

See [docs/adding-a-game.md](docs/adding-a-game.md).

## 2. Explore before implementing, one source, no legacy

- Before writing anything new, search the existing code (library and app) for something that already does it, or most
  of it, and reuse or extend it. This project is large: skipping this step is how near-duplicate helpers appear.
- Never keep two versions of the same thing. When a new mechanism replaces an old one, migrate every caller and delete
  the old code in the same change (or factor both into one).

## 3. Typed, explained code

- Closed sets are sealed classes / interfaces or enums, not raw strings. Strings are converted to types at the
  boundary only (e.g. an action received as JSON is parsed once into a `GameAction`, unknown values rejected with a
  typed error, never silently aliased).
- Clear layers behind interfaces (`ConsolePort`, `PokemonGame`, plans, registry). KDoc on public types and on
  anything non-obvious; comments explain **why**, with decompilation references when the behaviour comes from the game.
- Ids are never derived from the text shown on screen: the game may run in another language. Screens, menu entries
  and their ids (`option:yes`, `move:33`, `mon:…`, `person:3`) come from structure, positions, function pointers or
  game ids. Displayed labels are for display only.

## 4. Actions never press blindly

Every action that goes through screens or menus:

1. checks from RAM that it is on the **expected screen** before each step;
2. moves the cursor, **re-reads it**, and confirms only when it is on the target;
3. retries a wrong screen or cursor at most **3 times**, then stops with an explicit typed error (which screen,
   expected vs actual). It never confirms blindly.

This is a shared mechanism of the action layer (the `Navigator`), not re-implemented per action.

An action also accepts **every valid end screen** (e.g. saving from the start menu with buttons returns to the menu,
with the touch screen it returns to the overworld): recipes list the set of valid end states, never a single one, and
both input paths are tested when they exist.

## 5. The playing agent decides

Actions carry out what was asked; they don't take game decisions in the agent's place. When something happens
mid-action that changes the situation (a Repel wears off, a Pokémon faints, a battle ends, a scene starts), the action
**stops and returns a typed outcome**, and the agent chooses what to do next. Convenience behaviours (continue,
reapply a Repel, automatic choices...) exist only as **explicit opt-in parameter values**, documented in the action's
description and in the stop answer, never as the default.

## 6. Knowledge levels

What the state shows depends on `KnowledgeLevel` (only what the game shows / Pokédex / walkthrough):

- what a player can see on screen is never walkthrough knowledge: a blocking person, a scene that pushes the player
  back, a closed door, the reason a way is blocked as the screen shows it (`blocked_by`) are available at **every**
  level;
- walkthrough-only data is what a player can't see (story goals, trainers' teams, what lifts a blocker...);
- a message never refers to a field that is not sent at the current level;
- any information gated by a knowledge level is stated explicitly when it is introduced (in the PR / change
  description), so the choice can be confirmed.

## 7. Never write the game's RAM

Everything goes through buttons and the touch screen, like a player. The library never writes the game's RAM. The
one accepted exception is the sound playback state spliced on resume to keep music seamless across pauses (sound
blocks only, never anything the agent reads or decides on, can be disabled, per-game "supported" flag, tested to leave
non-sound RAM identical).

## 8. Kotlin Multiplatform: as much as possible in common

Both modules use the multiplatform plugin. Code and tests go in `commonMain` / `commonTest`; `jvmMain` / `jvmTest`
only hold the `actual`s of `expect`s declared in common (see `PlatformServices.kt`, `BundledResources.kt`,
`TestPlatform.kt`), so another target can be added by writing actuals. Files go through kotlinx-io, not `java.nio`.

## 9. Tests

- Every behaviour change ships with tests that lock **both** sides: the case being fixed **and** the existing case
  that must keep working (e.g. a warp planned in the path is taken / a warp not planned is never stepped on). Write
  the guard tests first, on the real ROM when the behaviour depends on game data.
- A test that targets code being removed or changed is **migrated** so every case it covered stays covered; only a
  case that truly no longer exists may go, with the reason given.
- Prefer fixtures captured on the bench (real RAM) over hand-written state.
- Run the whole suite in the three configurations before submitting:
  `POKEMON_ROM` + `PLATINUM_ROM`, `POKEMON_ROM` only, and no ROM (`./gradlew jvmTest`). Tests reading a ROM are
  skipped when its path isn't given. The app (ai-plays-pokemon) tests run against the library published to
  mavenLocal.

## 10. Versions and releases

Versions stay `-SNAPSHOT` in git. The maintainer sets the real version at release time and publishes; contributors
never change version numbers. Between releases, consumers use the snapshot through `./gradlew publishToMavenLocal`
(no composite builds / `includeBuild`).
