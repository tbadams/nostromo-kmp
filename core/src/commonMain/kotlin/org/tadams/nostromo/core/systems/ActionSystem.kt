package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.ActionDefs
import org.tadams.nostromo.core.action.ObedienceGate
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.ScheduledAction
import org.tadams.nostromo.core.model.World

/**
 * Starts queued actions, counts their timers down, and applies their effects when they finish.
 * What each action is allowed to do and what it does comes from its [org.tadams.nostromo.core.action.ActionDef].
 *
 * Timing: an action lasting N ticks takes N ticks of game time. An order applied on tick t
 * completes its first action on tick t + N - 1; each queued action then takes its own N ticks.
 */
internal object ActionSystem {

    /** Advances every actor's action by one tick. */
    fun run(world: World, scope: TickScope) {
        for (actor in world.actors.values) {
            if (actor.isDown) continue
            if (actor.current == null) startNext(world, actor, scope)
            val current = actor.current ?: continue
            current.remainingTicks--
            if (current.remainingTicks <= 0) {
                actor.current = null
                complete(world, actor, current.action, scope)
                // A delayed action is rescheduled as current, so only move on if it landed or failed.
                if (actor.current == null) startNext(world, actor, scope)
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
     * Queued actions are only checked here, when they start, and again when they land; nothing
     * re-checks the queue in between.
     *
     * Order of checks: capability, then the [ObedienceGate] (a Broken crew member panics whatever
     * the order, possible or not, as in the original), then the action's own rules.
     */
    private fun startNext(world: World, actor: Actor, scope: TickScope) {
        val next = actor.queue.removeFirstOrNull() ?: return
        // TODO decide whether to also check orders in Simulation.submit(), so the UI gets an immediate
        //  Rejected for impossible ones (not needed while the action bar only offers valid options).
        if (next.type !in actor.capabilities) {
            fail(actor, next, FailReason.NOT_CAPABLE, scope)
            return
        }
        when (val verdict = ObedienceGate.judge(actor, world, next, scope.rules)) {
            is ObedienceGate.Verdict.Refuse -> fail(actor, next, verdict.reason, scope)
            ObedienceGate.Verdict.Panic -> {
                actor.queue.clear()
                ObedienceGate.panic(actor, world, next, scope)?.let { begin(world, actor, it, scope) }
            }
            ObedienceGate.Verdict.Obey -> begin(world, actor, next, scope)
        }
    }

    /** Starts [action] now if its own rules allow it, otherwise fails it. */
    private fun begin(world: World, actor: Actor, action: Action, scope: TickScope) {
        val problem = ActionDefs.check(actor, world, action)
        if (problem != null) {
            fail(actor, action, problem, scope)
            return
        }
        val total = duration(actor, action, scope)
        actor.current = ScheduledAction(action, total)
        scope.emit(GameEvent.ActionStarted(scope.tick, actor.id, action, total, actor.selfVisibility()))
    }

    /**
     * Re-checks [action] just before applying it, since the world may have changed while it ran
     * (target left, item taken). If it's no longer possible it fails with no effect. If the
     * destination is full ([ObedienceGate.crowded]) it waits and tries again instead, keeping the
     * rest of the queue, as in the original (docs/mechanics.md §2).
     */
    private fun complete(world: World, actor: Actor, action: Action, scope: TickScope) {
        val problem = problemWith(world, actor, action)
        if (problem != null) {
            fail(actor, action, problem, scope)
            return
        }
        val holdUp = ObedienceGate.crowded(actor, world, action, scope.rules)
        if (holdUp != null) {
            actor.current = ScheduledAction(action, Time.ticks(scope.rules.crowdedWaitSeconds + actor.speedPenaltySeconds))
            scope.emit(GameEvent.ActionDelayed(scope.tick, actor.id, action, holdUp, actor.selfVisibility()))
            return
        }
        ActionDefs.complete(actor, world, action, scope)
    }

    /** Capability first, then the action's own rules. */
    private fun problemWith(world: World, actor: Actor, action: Action): FailReason? =
        if (action.type !in actor.capabilities) FailReason.NOT_CAPABLE else ActionDefs.check(actor, world, action)

    private fun fail(actor: Actor, action: Action, problem: FailReason, scope: TickScope) {
        actor.queue.clear()
        scope.emit(GameEvent.ActionFailed(scope.tick, actor.id, action, problem, actor.selfVisibility()))
    }

    /**
     * How many ticks [action] takes for [actor]: the base duration for its type (the actor's own if
     * they have one, otherwise the scenario's) plus the actor's speed penalty, for actions it slows.
     */
    private fun duration(actor: Actor, action: Action, scope: TickScope): Int {
        val base = actor.actionSeconds[action.type] ?: scope.rules.baseSeconds(action.type)
        // TODO add the wound penalty (mechanics.md §2: +1.2 s at health 3, +3.6 s at health 2) once actors have health.
        val penalty = if (action.type in scope.rules.unslowedActions) 0.0 else actor.speedPenaltySeconds
        return Time.ticks(base + penalty)
    }
}
