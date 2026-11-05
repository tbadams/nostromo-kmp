package org.tadams.nostromo.core

import org.tadams.nostromo.core.action.ActionOption
import org.tadams.nostromo.core.action.Command
import org.tadams.nostromo.core.action.CommandResult
import org.tadams.nostromo.core.action.Options
import org.tadams.nostromo.core.event.EventText
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.rng.SeededDice
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.Scenario
import org.tadams.nostromo.core.scenario.ScenarioValidator
import org.tadams.nostromo.core.systems.ActionSystem
import org.tadams.nostromo.core.systems.BrainSystem
import org.tadams.nostromo.core.systems.CommandSystem
import org.tadams.nostromo.core.systems.DetectionSystem
import org.tadams.nostromo.core.systems.EndSystem
import org.tadams.nostromo.core.systems.MoraleSystem
import org.tadams.nostromo.core.systems.RoleSystem

/**
 * The game. Randomness is delegated to the seeded [Dice].
 * [tick] deterministically advances the games state.
 *
 * Not thread-safe. Call [submit], [tick], [state] and [availableActions] from one thread or
 * coroutine context (the UI's main dispatcher), so that no two of them ever run at the same time.
 */
class Simulation(val scenario: Scenario, private val dice: Dice) {

    constructor(scenario: Scenario, seed: Long) : this(scenario, SeededDice(seed))

    init {
        ScenarioValidator.check(scenario)
    }

    /** Hidden roles, handed out before the first tick. */
    private val roles = RoleSystem.assign(scenario, dice)

    private val world = World.from(scenario, host = roles.host, android = roles.android)
    // Not thread-safe: written by submit() and drained by tick() with no lock.
    private val pending = mutableListOf<Command>()

    /** Each detector's last reported reading ([DetectionSystem]). */
    private val readings = mutableMapOf<ItemId, Boolean>()

    /** Text for this scenario's events and actions. */
    val text = EventText(scenario)

    /** Current time, in ticks. */
    var tick: Long = 0
        private set

    /** How the game ended, or null while it's still going. Once set, [tick] does nothing. */
    var result: GameResult? = null
        private set

    /**
     * Queues [command] to apply at the start of the next tick. Only crew can be given orders. An
     * order to crew who can't take it (down, or a revealed android) is accepted and quietly ignored,
     * so the player isn't told who the android is.
     */
    fun submit(command: Command): CommandResult {
        val actor = requireNotNull(world.actors[command.actor]) { "Unknown actor '${command.actor}'" }
        if (actor.kind != ActorKind.CREW) return CommandResult.Rejected("${actor.name} doesn't take orders")
        pending += command // Not thread-safe: unguarded write to pending.
        return CommandResult.Accepted
    }

    /** Advances one tick and returns what happened. Does nothing once the game is over. */
    fun tick(): List<GameEvent> {
        if (result != null) return emptyList()
        val scope = TickScope(tick, scenario.rules, dice)
        if (tick == 0L) roles.host?.let { scope.emit(GameEvent.Hatched(tick, it)) }
        // Not thread-safe: a submit() between this copy and the clear() would be lost.
        CommandSystem.run(world, pending.toList(), scope)
        pending.clear()
        ActionSystem.run(world, scope)
        world.carryCaught()
        RoleSystem.run(world, scope)
        BrainSystem.run(world, scope)
        MoraleSystem.run(world, scope)
        DetectionSystem.run(world, scope, readings)
        result = EndSystem.run(world, scope)
        tick++
        return scope.events
    }

    /** An immutable snapshot of the current state. */
    fun state(): GameState = GameState.of(world, scenario.rules, tick, result)

    /** Everything the actor specified by [actorId] could be ordered to do right now, for the action bar. */
    fun availableActions(actorId: ActorId): List<ActionOption> {
        val actor = world.actors[actorId] ?: return emptyList()
        if (result != null || !actor.takesOrders) return emptyList()
        return Options.valid(actor, world, scenario.rules).map { ActionOption(text.option(it), it) }
    }
}
