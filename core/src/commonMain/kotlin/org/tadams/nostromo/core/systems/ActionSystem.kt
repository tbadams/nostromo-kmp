package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.ScheduledAction
import org.tadams.nostromo.core.model.World

/**
 * Starts queued actions, counts their timers down, and applies their effects when they finish.
 *
 * Timing: an action lasting N ticks takes N ticks of game time. An order applied on tick t
 * completes its first action on tick t + N - 1; each queued action then takes its own N ticks.
 */
internal object ActionSystem {

    /** Advances every actor's action by one tick. */
    fun run(world: World, scope: TickScope) {
        for (actor in world.actors.values) {
            if (actor.current == null) startNext(world, actor, scope)
            val current = actor.current ?: continue
            current.remainingTicks--
            if (current.remainingTicks <= 0) {
                actor.current = null
                complete(world, actor, current.action, scope)
                startNext(world, actor, scope)
            }
        }
    }

    /** Drops the current action (with no effect) and the queue. */
    fun cancel(actor: Actor, scope: TickScope) {
        val current = actor.current
        actor.current = null
        actor.queue.clear()
        if (current != null) {
            scope.emit(GameEvent.ActionCancelled(scope.tick, actor.id, current.action, actor.selfVisibility()))
        }
    }

    /**
     * Starts [actor]'s next queued action, or fails and clears the queue if it isn't possible.
     * Queued actions are only checked here, when they start; nothing re-checks the queue as the
     * world changes.
     */
    private fun startNext(world: World, actor: Actor, scope: TickScope) {
        val next = actor.queue.removeFirstOrNull() ?: return
        // TODO decide whether to also check orders in Simulation.submit(), so the UI gets an immediate
        //  Rejected for impossible ones (not needed while the action bar only offers valid options).
        val failure = problemWith(world, actor, next)
        if (failure != null) {
            actor.queue.clear()
            scope.emit(GameEvent.ActionFailed(scope.tick, actor.id, next, failure, actor.selfVisibility()))
            return
        }
        val total = duration(actor, next, scope)
        actor.current = ScheduledAction(next, total)
        scope.emit(GameEvent.ActionStarted(scope.tick, actor.id, next, total, actor.selfVisibility()))
    }

    /** Why [action] can't start right now, or null if it can. */
    private fun problemWith(world: World, actor: Actor, action: Action): FailReason? = when (action) {
        is Action.Move ->
            if (world.room(actor.room).exits.none { it.to == action.to }) FailReason.NOT_ADJACENT else null
    }

    /**
     * How many ticks [action] takes for [actor]: the base duration for its type (the actor's own if
     * they have one, otherwise the scenario's) plus the actor's speed penalty.
     */
    private fun duration(actor: Actor, action: Action, scope: TickScope): Int {
        val base = actor.actionSeconds[action.type] ?: scope.rules.baseSeconds(action.type)
        // TODO add the wound penalty (mechanics.md §2: +1.2 s at health 3, +3.6 s at health 2) once actors have health.
        return Time.ticks(base + actor.speedPenaltySeconds)
    }

    /** Applies [action]'s effect on the world. */
    private fun complete(world: World, actor: Actor, action: Action, scope: TickScope) {
        // TODO decide whether to re-check the action here, just before applying it, since the world may
        //  have changed while it ran (item taken, target left, room filled, airlock blown). A failed
        //  re-check would either fail the action or wait and retry, as the original's room-full rule
        //  does (mechanics.md §2). Moves can't go stale yet, so add this with items or room capacity.
        when (action) {
            is Action.Move -> {
                val from = actor.room
                actor.room = action.to
                val witnesses = (world.occupants(from) + world.occupants(action.to))
                    .filter { it.isCrew }
                    .mapTo(mutableSetOf()) { it.id }
                scope.emit(GameEvent.ActorMoved(scope.tick, actor.id, from, action.to, Visibility.Perceived(witnesses)))
            }
        }
    }
}
