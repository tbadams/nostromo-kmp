package org.tadams.nostromo.core

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.ActionOption
import org.tadams.nostromo.core.action.Command
import org.tadams.nostromo.core.action.CommandResult
import org.tadams.nostromo.core.event.EventText
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.rng.SeededDice
import org.tadams.nostromo.core.scenario.Scenario
import org.tadams.nostromo.core.scenario.ScenarioValidator
import org.tadams.nostromo.core.systems.ActionSystem
import org.tadams.nostromo.core.systems.CommandSystem
import org.tadams.nostromo.core.systems.TickScope

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

    private val world = World.from(scenario)
    // Not thread-safe: written by submit() and drained by tick() with no lock.
    private val pending = mutableListOf<Command>()

    /** Text for this scenario's events and actions. */
    val text = EventText(scenario)

    /** Current time, in ticks. */
    var tick: Long = 0
        private set

    /** Queues [command] to apply at the start of the next tick. */
    fun submit(command: Command): CommandResult {
        require(command.actor in world.actors) { "Unknown actor '${command.actor}'" }
        // TODO reject orders to actors who can't take them. Today any actor is accepted, so the creature
        //  or cat could be ordered around. "Takes orders" isn't simply kind == CREW: the android is crew
        //  until revealed, then behaves like a creature, and dead crew can't act. It likely depends on
        //  kind plus state (alive, revealed). See the TODO on ActorKind about the android.
        pending += command // Not thread-safe: unguarded write to pending.
        return CommandResult.Accepted
    }

    /** Advances one tick and returns what happened. */
    fun tick(): List<GameEvent> {
        val scope = TickScope(tick, scenario.rules, dice)
        // Not thread-safe: a submit() between this copy and the clear() would be lost.
        CommandSystem.run(world, pending.toList(), scope)
        pending.clear()
        ActionSystem.run(world, scope)
        tick++
        return scope.events
    }

    /** An immutable snapshot of the current state. */
    fun state(): GameState = GameState.of(world, tick)

    /** Everything the actor specified by [actorId] could be ordered to do right now, for the action bar. */
    fun availableActions(actorId: ActorId): List<ActionOption> {
        val actor = world.actors[actorId] ?: return emptyList()
        return world.room(actor.room).exits.map { exit ->
            val move = Action.Move(exit.to)
            ActionOption(text.option(move), move)
        }
    }
}
