# Architecture (proposal)

## Goals

- **Shareable on the web:** a static Wasm site, plus desktop for development. Android is kept but low priority.
- **Rules separate from UI:** the simulation is plain Kotlin that can be tested on its own (see [mechanics.md](mechanics.md) and [brainstorm.md](brainstorm.md)).
- **Deterministic:** the same seed and the same commands give the same game. That makes bugs reproducible and seeds shareable.
- **Data-driven:** the map, crew, items and tuning numbers come from scenario data, so they can be loaded or randomized.

## Modules

```
:core          pure Kotlin Multiplatform (jvm, wasmJs, android): the game simulation. No Compose.
:shared        Compose Multiplatform UI + GameViewModel. Depends on :core.
:desktopApp    JVM entry point. The day-to-day development and debugging target.
:webApp        wasmJs entry point. Builds the static site.
:androidApp    Android entry point. Kept building, low priority.
```

**Rule:** `:core` has no UI or platform dependencies apart from `kotlinx-serialization`. The UI reads snapshots and sends commands. Nothing in `:core` knows Compose exists.

## Core design

### Mutable world, immutable view

- A `Simulation` owns a mutable `World` (rooms, actors, items, ship state). Writing rules as plain mutations, like `actor.health -= 1`, stays simple. Doing it through deeply nested `copy()` calls would be painful.
- The UI never touches `World`. It gets an immutable `GameState` snapshot after each tick, built from data classes, so Compose can tell what changed.
- Determinism doesn't need immutability. It needs (a) one seeded RNG, (b) a fixed order for the systems that run each tick, and (c) every input arriving as a `Command`. A replay is just the seed plus a list of `(tick, command)`.

```kotlin
class Simulation(scenario: Scenario, seed: Long) {
    fun submit(command: Command): CommandResult   // validated, queued for the next tick
    fun tick(): List<GameEvent>                   // advance one tick
    fun state(): GameState                        // immutable snapshot for the UI
    fun availableActions(actor: ActorId): List<ActionOption>  // drives the flat action bar
    val result: GameResult?                       // non-null when the game is over
}
```

### Time

- All durations in `:core` are in **ticks**, as in the original (see mechanics §2).
- **Fixed tick rate.** A tick is always the same length of real time: **10 ticks per second (100 ms)**. Ticks ÷ 10 = seconds.
  - No speed controls for now. A speed scaler would be easy to add later, but difficulty is better tuned through rules (timers, alien behaviour, oxygen) than through game speed.
  - The original ran at about 13.5 ticks per second (see mechanics §2). To keep its pace, convert its durations to **seconds** (original ticks ÷ 13.5) and then to our ticks. Never reuse its raw tick counts, which would make everything about 35% slower.
  - Progress bars and animation can update every frame by interpolating between ticks.
  - The original's ticks varied in length because sounds blocked its main loop, so busy moments slowed game time. That won't happen here.
  - The UI loop counts how much real time has passed and runs any ticks it missed, so the rate stays constant even when a frame runs late.
  - The game pauses automatically when the browser tab or window is hidden. Otherwise, coming back would run minutes of missed ticks at once.
- **Displayed times are real time.** Any countdown or duration shown to the player is converted from ticks to real seconds at the fixed rate.
  - Example: a destruct countdown of 7 minutes' worth of ticks (4200) shows as **7:00** and counts down one second per real second.
  - The original did this differently: its "5:00" clock used 20 ticks per displayed second, so it actually lasted about 7.4 minutes. We don't copy that.
  - So `Rules` can give durations in seconds for readability, and `:core` converts them to ticks once at load (`ticksPerSecond` is a field in `Rules`). The display converts back the same way.
- `:core` never sleeps or launches coroutines. That keeps tests instant: a test can run 6000 ticks in milliseconds.

### Actions and commands

- `Command` is input from the player: `Order(actor, action)`, `Cancel(actor)`, `Select(actor)`, and so on.
- `Action` is something an actor is doing, with a duration: `Move`, `UseGrille`, `RemoveGrille`, `Attack`, `PickUp`, `Hypersleep`, and so on. Each actor has a **current action** (with progress, which the UI shows) and a **queue**. Click-to-move pushes several `Move`s found by a breadth-first search over the room graph.
- `availableActions(actor)` returns a flat list of options, e.g. "Get Laser Pistol" or "Attack Ash". The UI renders it directly, so there are no nested menus.
- **Busy actors: a new order replaces the current one.** If you order a busy character to do something else, their current action and queue are cancelled and the new order starts. For example, "Get out of there!" interrupts a grille removal.
  - So the scheduler must support **cancelling** actions:
    - `Cancel(actor)` is also a command in its own right.
    - Cancelling clears the actor's timer and queue, emits an `ActionCancelled` event, and leaves the world as it was before the action resolved. Actions only change the world when they complete, so a cancelled action has no partial effects.
  - Action buttons stay enabled while a character is busy. The UI shows the current action and its progress, so it's clear what's being interrupted.
  - Kept simple for now. Other policies (reject while busy, queue) can come back as a rule setting if replace doesn't feel right.

### Systems, run in a fixed order each tick

Each system is a small function over `World` plus a `TickScope` (dice, event sink, rules). The order is set once in `Simulation`, which mirrors the original's main loop (mechanics §11):

1. `CommandSystem`: apply queued commands
2. `ActionSystem`: count down timers and resolve finished actions, passing them through the obedience and panic gate
3. `BrainSystem`: AI for the creature, the android, the cat and crew reflexes (actors with an AI decide when they're idle)
4. `MoraleSystem`: recompute effective morale where something changed
5. `HazardSystem`: room damage, fire and spread, oxygen
6. `ShipSystem`: destruct clock, airlocks, launch checks
7. `DetectionSystem`: trackers, sounds, the cat's unease
8. `EndSystem`: win and lose checks, and scoring

Each system is unit-testable on a tiny hand-built world.

### Brains (AI)

- Behaviour is pluggable through `interface Brain { fun decide(actor, world, scope): Action? }`, chosen by an id in the scenario data. nostromo-js used `"ai": "wumpus"` the same way.
- The first brains:
  - `RandomWanderCreature`: faithful to the original
  - `AndroidBrain`
  - `CatBrain`
  - `CrewReflexes`: when threatened, attack or flee with odds based on morale and weapons, otherwise do nothing (brainstorm §3)
- Smarter creatures (sabotage to lure crew out, attraction to noise) are new brains or rule flags. Scenarios can switch them on.

### Events and the log

- Every state change emits a `GameEvent`, a sealed class with structured fields such as `ActorMoved`, `AttackHit`, `BodyFound`, `FireStarted` and `DiceRolled(reason, roll, needed)`.
- Each event has a **visibility**:
  - `Perceived(by = setOf(actorIds))`: seen or heard by these crew
  - `Ship`: an alarm or announcement everyone gets
  - `Hidden`: AI decisions, rolls, timers, hidden roles. Shown only in debug.
- The log in the UI filters by visibility. The debug switch just shows `Hidden` events too.
- Text for events comes from one formatter in `:core`, `EventText`, so tests can assert on wording and every platform shows the same text.

### Randomness

- A `Dice` wrapper around `kotlin.random.Random(seed)`. Kotlin's `Random(seed)` gives the same results on every platform.
- Dice calls take a `reason`, so in debug every roll is logged with what it was for. Tests can inject a scripted `Dice` to force outcomes.

### Scenario and rules data

- `@Serializable` data classes, loaded from JSON:
  - `Scenario`: rooms, typed links (door, ladder, grille/duct, one-way), decks, crew definitions with stats, items and where they start, specials (airlocks, cryo, shuttle), hidden-role rules ("any", a weighted list, or "none")
  - `Rules`: every tuning number from mechanics.md (timers, damage thresholds, flee formula, oxygen rate, …), with **the original's values as defaults**. A scenario overrides only what it changes.
- **Validation on load:** the map is connected and every reference resolves. nostromo-js already checked connectivity.
- **Randomizer:** a function from (base scenario, seed) to a scenario, which shuffles crew stats, roles, item placement and so on.
- **Where files live:**
  - The bundled scenarios go in `:shared` as Compose resources (`composeResources/files/scenarios/*.json`). The UI loads one and hands it to `:core`.
  - `:core` tests build small scenarios in code with a builder.

## Package layout (`:core`, `org.tadams.nostromo.core`)

```
model/      World, Room, Link, Actor, Item, ids (value classes), ShipState
scenario/   Scenario, Rules, ScenarioLoader, ScenarioValidator, Randomizer
action/     Command, Action, ActionOption, availability, pathfinding
systems/    the eight systems above + TickScope
ai/         Brain + implementations
event/      GameEvent, Visibility, EventText
rng/        Dice
Simulation.kt, GameState.kt, GameResult.kt
```

## UI integration (`:shared`)

- A `GameViewModel` holds the `Simulation`, runs a coroutine tick loop at the fixed 10 ticks per second, and exposes:
  - `StateFlow<GameState>` for the current state
  - `StateFlow<List<LogEntry>>` for the log
  - `submit(command)` for input
- The screen is made of separate composables: crew tabs, the action bar, the map (Canvas), the log, the stats sheet. Each reads only the part of `GameState` it needs.
- Keyboard shortcuts (number keys, next/previous crew) and mouse clicks both turn into the same `Command`s.

## Testing

- `:core:jvmTest` runs the shared tests on the JVM, which is the fastest loop.
- **Rule tests:** one per mechanic in mechanics.md, e.g. the morale formula, room capacity, flee chance, the android reveal at 6 damage.
- **Scenario tests:** every bundled JSON file loads and passes validation.
- **Determinism test:** the same seed and commands give the same event list. Golden replays guard against regressions.

## Build changes needed

- Add the `org.jetbrains.kotlin.plugin.serialization` plugin, at the Kotlin version, and `kotlinx-serialization-json` to `libs.versions.toml`. The versions will be checked when the module is created.
- New `core/build.gradle.kts`, using the same `jvm`, `wasmJs` and `android` target setup as `:shared`, but without the Compose plugins.
- `include(":core")` in `settings.gradle.kts`, and `implementation(project(":core"))` in `:shared`.

## Approach: build order

Get a playable vertical slice working as early as possible, then add depth.

1. **Skeleton:** the `:core` module; `World`, `Scenario` and `Rules` models; loader and validator; `Dice`; `Simulation` with timed `Move` and the event log; tests.
2. **Vertical slice:** the creature wanders and attacks, crew move and attack with one weapon, a win/lose check. A minimal UI in `:shared` (crew tabs, flat action bar, log, a text map) shows the loop working end to end.
3. **Crew depth:** items and inventory, morale and obedience, room capacity, the panic gate.
4. **Threats:** the full combat table, flee rolls, the android and chestburster with any-crew roles, the cat, the tracker and sounds.
5. **Ship:** room damage, fire and spread, oxygen, self-destruct, airlocks, launch, endings, Competence Rating.
6. **New ideas:** crew reflexes, a creature that sabotages rooms, click-to-move queues, the randomizer, the stats screen.

UI work in `:shared` can run alongside steps 3–6 once the vertical slice's `GameState` exists.

### Post-hackathon

Worth doing, but not needed for the hack.

- **Localizable strings.** Move all player-facing text out of `:core` into Compose Multiplatform string resources (`composeResources/values/strings.xml`, the KMP equivalent of Android's `strings.xml`, with `values-xx/` translations).
  - `:core` emits only structured events and reasons, as it mostly does already, and stops building sentences: `EventText` and `ActionOption.label` go.
  - A formatter in `:shared` turns each `GameEvent`, `FailReason` and action into a string resource with arguments.
  - `:core` tests then check event fields, not English wording. A few `:shared` tests check the text.
  - Note: `getString` is `suspend` outside composables, so log text is formatted in the UI layer rather than in the view model's tick loop.

## Decisions

- **Tick rate:** fixed at 10 per second. There are no speed controls, and difficulty is tuned through rules (see "Time").
- **Busy actors:** a new order replaces the current one, and the scheduler supports cancelling (see "Actions and commands").
- **First scenario:** the original's map structure (rooms, links, ducts, decks; see mechanics §3), with our own names and setting. Other maps can come later if time allows. The data-driven scenario format is meant to make that easy.
- **Android:** keep the target building, but it's low priority. Desktop is for development and web is for sharing. Avoid anything that would break Android, but don't spend time testing it.
