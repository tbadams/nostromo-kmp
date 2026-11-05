package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.action.Options
import org.tadams.nostromo.core.ai.Brains
import org.tadams.nostromo.core.model.World

/**
 * Gives every idle, AI-driven actor its next action, chosen from what [Options] says it can do.
 * Runs after [ActionSystem], so an actor that finishes something this tick decides straight away
 * and starts on the next tick.
 */
internal object BrainSystem {
    fun run(world: World, scope: TickScope) {
        for (actor in world.actors.values) {
            val brainId = actor.brain ?: continue
            if (actor.isDown || actor.current != null || actor.queue.isNotEmpty()) continue
            if (actor.frozenUntil > scope.tick || actor.caughtIn != null) continue
            val options = Options.valid(actor, world, scope.rules)
            if (options.isEmpty()) continue
            Brains.get(brainId).decide(actor, options, world, scope)?.let(actor.queue::addLast)
        }
    }
}
