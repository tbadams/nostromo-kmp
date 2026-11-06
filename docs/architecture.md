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
  - No speed controls for now. A speed scaler would be easy to add later, but difficulty is better tuned through rules (timers, alien behavior, oxygen) than through game speed.
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
- `Action` is something an actor is doing, with a duration: `Move`, `RemoveGrille`, `Attack`, `Get`, `Drop`, `Swap`, later `Hypersleep`, and so on. Going through an open grille is a `Move`. Each actor has a **current action** (with progress, which the UI shows) and a **queue**. Click-to-move pushes several `Move`s found by a breadth-first search over the room graph.
- `availableActions(actor)` returns a flat list of options, e.g. "Get Laser Pistol" or "Attack Creature". The UI renders it directly, so there are no nested menus.
- **One definition per action type** (`ActionDef`: `candidates`, `check`, `complete`) holds that action's rules. `Options.forActor` combines them with the actor's **capabilities** (which action types it can ever take; defaults per actor kind, overridable in the scenario) into the list of what it can do right now. The action bar and brains both use that list, and the scheduler runs the same `check` when an action starts and again when it lands, so what's offered, allowed and applied can't drift apart.
- **Busy actors: a new order replaces the current one.** If you order a busy character to do something else, their current action and queue are cancelled and the new order starts. For example, "Get out of there!" interrupts a grille removal.
  - So the scheduler must support **cancelling** actions:
    - `Cancel(actor)` is also a command in its own right.
    - Cancelling clears the actor's timer and queue, emits an `ActionCancelled` event, and leaves the world as it was before the action resolved. Actions only change the world when they complete, so a cancelled action has no partial effects.
  - Action buttons stay enabled while a character is busy. The UI shows the current action and its progress, so it's clear what's being interrupted.
  - Kept simple for now. Other policies (reject while busy, queue) can come back as a rule setting if replace doesn't feel right.

### Systems, run in a fixed order each tick

Each system is a small function over `World` plus a `TickScope` (dice, event sink, rules). The order is set once in `Simulation`, which mirrors the original's main loop (mechanics §11):

1. `CommandSystem`: apply queued commands
2. `ActionSystem`: count down timers and resolve finished actions, passing them through the obedience and panic gate (then caught animals move with their holders)
3. `RoleSystem`: the android's reveal (roles are handed out once, before the first tick)
4. `BrainSystem`: AI for the creature, the android, the cat and crew reflexes (actors with an AI decide when they're idle)
5. `MoraleSystem`: the morale rules (base and effective morale, worked out on demand) and the events that change base morale
6. `HazardSystem`: room damage thresholds, fire, oxygen
7. `ShipSystem`: the destruct clock (arming, airlocks and launch are actions)
8. `DetectionSystem`: trackers, sounds, the cat's unease
9. `EndSystem`: win and lose checks, and scoring

Each system is unit-testable on a tiny hand-built world.

### Brains (AI)

- Behavior is pluggable through `interface Brain { fun decide(actor, options, world, scope): Action? }`, chosen by an id in the scenario data. nostromo-js used `"ai": "wumpus"` the same way. A brain only chooses from `options` (what `Options` says the actor can do), as nostromo-js's AI chose from `Action.getChoices`; it never invents actions.
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

### Narrator: from events to the log

Today each event maps to zero or one log line, decided on its own with no memory (`GameViewModel.toLogEntry` + `EventText.describe`). That can hide and reword events, but it can't tell a story: it can't merge several events into one line, react to what came before, update an earlier line, or tailor lines to who's watching.

**Plan (build step 3):** a **narrator** in `:shared`, between the event stream and the log.
- It receives every `GameEvent` in order and produces log entries. It's a small state machine with memory, e.g. "the fight between the creature and X is already reported".
- It can:
  - **merge** many events into one line: one "The creature is attacking X" instead of a line per hit
  - **escalate** only on meaningful change: "is being wounded" when health crosses a threshold, as the original does (docs/mechanics.md §6)
  - **update in place:** upgrade an earlier line rather than add a new one
  - **delay or suppress** lines
  - **tailor** lines to the selected crew member
- All log quality-of-life changes live here, without touching the rules.

**Responsibilities stay split:**
- **`:core` reports the truth:** structured events with precise facts, and who could have perceived each one. That is a game rule, not presentation, so it stays in the simulation.
  - `Perceived` doesn't tell an actor's own experience apart from witnesses; the narrator treats an event's subject (e.g. the one going down) as not being a witness.
- **`:shared` decides how to tell it:** grouping, wording, emphasis, timing.
- **Debug** still shows every raw event, dimmed, beside the narrated log, so nothing is lost when the narrator merges or hides events.

**First case (done, `game/Narrator.kt`):** fights: one entry when a fight starts, a line when the victim gets worse, nothing per hit, and a death only if another crew member saw it.

**Later:** the post-hackathon localization work moves `EventText`'s wording into string resources the narrator uses.

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
action/     Command, Action, ActionDef + one per action type, Options, pathfinding
systems/    the systems above
ai/         Brain + implementations
event/      GameEvent, Visibility, EventText
rng/        Dice
Simulation.kt, TickScope.kt, GameState.kt, GameResult.kt
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
3. **Crew depth:** first the narrator (see "Narrator: from events to the log"), so this step's new events are designed to be narrated; then items and inventory, morale and obedience, room capacity, the panic gate.
   - Done: the narrator; items (one in use, one carried; instant Get, Drop and Swap); weapon damage from the in-use item (unarmed crew do nothing); effective morale (`systems/MoraleSystem.kt`); the obedience gate (`action/ObedienceGate.kt`: below morale 2, no attacking or entering ducts). Grilles: each room's grille to its duct blocks the way until removed (`RemoveGrille`, from either side; per-crew times; the creature tries it 3/16 of the time).
   - Also done: panic at morale 0 (drop everything and run; in a duct, rip out the grille and climb out); −1/+1 base morale entering/leaving ducts; finding bodies (−1 to all crew, and the UI shows crew as down only once found); room capacity (3 per room, 1 per duct, bodies count; a move into a full place waits and retries). All tuning numbers are in `Rules`.
   - Differs from the original, on purpose for now: the obedience gate runs when an action starts, not when it lands; bodies are found as soon as someone shares their location, not on the next arrival; a panicking crew member always runs if there's a way out.
4. **Threats:** the full combat table, flee rolls, the android and chestburster with any-crew roles, the cat, the tracker and sounds.
   - Done: item charges and effects on `ItemTypeDef` (laser and harpoon room damage, the harpoon killing the room, the net freezing, the tracker and net breaking, the extinguisher scaring); driving the creature off (`AttackDef`), with +1 morale per attacker up to 8; the wound penalty on action times; the creature's faster grille passage (`ActorDef.grilleMoveSeconds`) and its 6/16 preference for an open grille.
   - Roles (`scenario.roles`, `systems/RoleSystem.kt`): "none", "any" or a weighted list for the android and the host. The host is out of play from the start; the android is crew until the creature has taken 6 damage, then turns (brain `"android"`). Before that it never attacks the creature (the order becomes a move away) and skips the morale check. The reveal is `Hidden`: the UI shows the android as NO RESPONSE, and crew learn the truth by seeing it attack. A revealed android counts for neither morale, witnesses nor the ending.
   - The cat (brain `"cat"`, `CatchDef`): wanders rooms, never ducts, avoids the creature. Caught with a box or net in use (per-crew difficulty), it rides in the item, works as a detector and gives +1 morale.
   - Detection and sounds (`systems/DetectionSystem.kt`): door and grille sounds as ship-wide `Noise` events. For the MVP the narrator logs only the grille sound ("A grille clangs somewhere"), and only when nobody saw the grille come off; door sounds are far too many to log and wait for real audio in step 6. Detectors (tracker, caught cat) report changes to the holder, and an all-clear gives +1 effective morale.
   - Differs from the original: the tracker reports each holder's reading, not just the selected crew member's; the cat avoids the creature's room rather than moving again after landing in it; anyone can be the android or the host.
5. **Ship:** room damage, fire and spread, oxygen, self-destruct, airlocks, launch, endings, Competence Rating.
   - Done (`systems/HazardSystem.kt`, `action/ShipDefs.kt`, `systems/EndSystem.kt`): special rooms are `RoomDef.features` (destruct and airlock controls, airlocks, intruder entry, hypersleep, launch, flammable, key systems). At 30% damage flammable rooms catch fire and key systems warn; at 70% a structural warning; any room at 100% blows up the ship. Fire adds 5% every 7.4 s until fought out with an extinguisher (1/2/3 goes by damage). The self-destruct runs 7:00 real time. Airlocks lose everything inside; an unhurt creature survives 2/16 and comes back at the shuttle bay. Hypersleep takes crew out of play. Launch needs every crew member in play (an unrevealed android too) and the cat aboard. All six endings and the Competence Rating table from mechanics §10.
   - UI: oxygen and the destruct clock in the status bar; FIRE / STRUCTURAL / BLOWN on the map; an end screen with the rating in parts.
   - Differs from the original: oxygen is used per breathing crew member (its 2 × N rate drained fastest with one missing); the destruct runs 7:00 real time (its "5:00" was about 7.4 minutes); the fire-fighting count resets once a fire is out; fire never spreads, as there.
6. **New ideas:** crew reflexes, click-to-move queues, the randomizer.
   - TODO real audio for the door and grille sounds (and the tracker's beep). Today `:core` emits them (`GameEvent.Noise`); only the grille sound reaches the player, as a log line; playing a sound needs a small `expect`/`actual` audio player per platform in `:shared`.
   - TODO randomizer: the cat's start room. The original picks a random room 0–15 (`reference/notes/20_commands_crew.md:29`): the first 16 in its room table (airlocks, armory, cargo pods, command center, computer, corridors 1–7, cryo vault). Ours always starts in the Recreation Area, which the original's pick can never land on. Roll it from the game seed, from a list of candidate rooms the scenario gives.
   - Narrator idea: hide the "enters …" log lines for crew members' own moves, since the player ordered them and the map shows them. Keep them for the creature, the cat and anything else that isn't crew. (The "starts …" lines are already gone: the narrator logs no action starts except attacks.)

UI work in `:shared` can run alongside steps 3–6 once the vertical slice's `GameState` exists.

### Post-hackathon

Worth doing, but not needed for the hack.

- **Localizable strings.** Move all player-facing text out of `:core` into Compose Multiplatform string resources (`composeResources/values/strings.xml`, the KMP equivalent of Android's `strings.xml`, with `values-xx/` translations).
  - `:core` emits only structured events and reasons, as it mostly does already, and stops building sentences: `EventText` and `ActionOption.label` go.
  - A formatter in `:shared` turns each `GameEvent`, `FailReason` and action into a string resource with arguments.
  - `:core` tests then check event fields, not English wording. A few `:shared` tests check the text.
  - Note: `getString` is `suspend` outside composables, so log text is formatted in the UI layer rather than in the view model's tick loop.
- **Interrupting AI decisions.** Today, as in the original, a brain decides only when its actor is idle and then commits: crew who walk into the creature's room just after it chose to move see it leave. Options, each behind a `Rules` flag so the original's behavior stays the default:
  - **Interrupt on arrival:** when crew enter the creature's location, cancel its pending move or prowl so its brain decides again that tick. Cheap, since cancelling has no partial effect.
  - **General re-check:** an optional brain hook ("abandon this?") run each tick or on chosen events. More flexible, more machinery.
  - **Victim chosen when the attack lands:** the original picks whom to hit from whoever is present when the attack resolves; ours fixes the target at decision time, so an attack fails if that one target leaves even with others still there.
- **Log each crew hit.** Today the narrator folds every fight into "X is attacking Y" plus one "Y is being wounded", so the player never sees their own crew's individual hits. Show each hit by crew ("Roby hits Creature", "…, to no effect"); keep folding the creature's.
  - About 10 lines in `game/Narrator.kt` and a test. The narrator needs to know who's crew: pass it the crew ids from `GameViewModel`.
  - Undecided: whether to drop the "is attacking" opening line for crew fights, so each swing is told once; and whether the revealed android's hits count as crew (it's crew by id; telling them apart needs a loyal-crew flag on `GameEvent.AttackHit`).
- TODO **Typewriter effect** for the end-of-game report and new log lines: text appears a character at a time, as on a terminal.
- TODO **Center the end-of-game report text** (`EndScreen` in `game/GameScreen.kt`); today it's left-aligned.
- TODO **Spacing the cat loses the game, immediately.** Today a cat blown out of an airlock (loose, or caught in an item inside) is just gone, and since launch only waits for animals still in play, it's a shortcut past catching it. Make it a new `Ending` in `EndSystem`, with its own ending text and a rating of 0.
- **Android and mobile web support.** Prototype: below 600dp wide, `GameScreen` switches to `CompactGame` (portrait: the map on top, scrolling so rooms stay at least 48dp tall; crew chips; a two-line log ticker that opens the full log; a grid of action buttons, without moves to cyan rooms since tapping the room does that).
  - TODO try it on a real phone and tune: map cell height, text size (10sp in room boxes is small), how many action rows fit.
  - TODO landscape phones: under 600dp tall, the wide layout gets a squashed map. Needs its own arrangement, or a lock to portrait.
  - TODO the menu and instructions screens on phones; the end screen hides the log when narrow.
  - TODO check the game pauses when the app goes to the background (`GameViewModel.pause()`).
- "Jumpscare" screen when alien present, like in original
- Crew should refuse to enter airlock room altogether when it is blown, not agree to then end up in c6 again.
- Difficulty/a11y options like toggling off 02 consumption or changing value
- Add back short game from original
- Prettier Map, more like orig, maybe as optional add on for scenario
- The duct map nodes are too damn small . separately or in addition, a11y clickability for map nodes
- More varied / complex AI types
  - "Storyteller" amp up aggression / cheat towards people as goes longer without interaction, give break after
  - hunter: keep cache of map tiles searched and pick them over looking for crew
  - stealth: when cheat, prioritize lone crew
  - sabatoeur: mre intentional about damaging the ship, somehow
  - brainstorm more
- Current'y says "Creature opens grate in Room" when a grille opens under you, might be more immersive to say "grate opnes"

## Decisions

- **Tick rate:** fixed at 10 per second. There are no speed controls, and difficulty is tuned through rules (see "Time").
- **Busy actors:** a new order replaces the current one, and the scheduler supports cancelling (see "Actions and commands").
- **First scenario:** the original's map structure (rooms, links, ducts, decks; see mechanics §3), with our own names and setting. Other maps can come later if time allows. The data-driven scenario format is meant to make that easy.
- **Android (platform):** keep the target building, but it's low priority. Desktop is for development and web is for sharing. Avoid anything that would break Android, but don't spend time testing it.
- **Seconds in data, ticks in the simulation.** Scenario data gives durations in seconds; `:core` counts ticks.
  - The original ran at about 13.5 ticks per second (measured; see mechanics §2), so convert its durations to seconds first.
  - Displayed timers are real time: 7 minutes of ticks shows as 7:00.
- **Actions are checked three times:** when offered, when they start, and again just before they land. A cancelled action has no partial effect.
- **`Simulation` owns `tick` and `result`,** with private setters. They're kept out of `World` on purpose, because every system can change `World`.
- **The creature never hunts,** as in the original. It attacks crew in its room, and otherwise wanders or prowls (lingers, damaging the room), weighted so dead ends make it linger.
- **Brains only choose from `Options`;** they never invent actions.
- **Crew stand-ins for the original's seven** (stats in mechanics §4; table in `tools/make_scenario.py`):
  - Martin = Dallas, Broussard = Kane, Roby = Ripley, Hill = Ash (inferred, unconfirmed), Melkonis = Lambert, Hunter = Parker, Faust = Brett.
  - Health is the original's minus 1, because its crew collapse below 2.
- **Morale is worked out from the world, not stored.** Base morale is the actor's own plus the in-use item's bonus, and effective morale adds company and bodies (mechanics §5). The original updated both on item changes and arrivals; computing them live gives the same numbers without having to undo bonuses on every way an item can leave.
- **No friendly fire by default.** As in the original, crew can attack only the creature (later also the revealed android). `Rules.friendlyFire` turns crew-on-crew attacks back on; the `:core` test fixtures switch it on because it's the simplest way to cause damage.
- **The obedience gate doesn't hide options.** A frightened crew member is still offered "Attack" and refuses it when it starts (`TOO_SCARED`), as in the original, so the player learns why.
- **UI look:** terminal style. Green outlines with a faded fill, not Material defaults. The palette is in `shared/.../ui/Colors.kt`.

### Known architectural debt

Tagged with TODOs in the code:
- **Speed values are in seconds on `Actor`.** That breaks "seconds in data, ticks in the simulation"; convert in `World.from()`.
- **Allegiance is flags on `Actor`,** not a model of its own: `isAndroid`, `revealed`, `isLoyalCrew` and `takesOrders`. Orders to non-crew are rejected; orders to a revealed android are accepted and ignored, so `submit()` doesn't give the secret away.
- **`ActionDefs` looks up definitions by type with one unchecked cast.** Moving `check`/`complete` onto the `Action` subtypes was considered and deferred; it's cheapest to do before step 3 adds a batch of actions.
