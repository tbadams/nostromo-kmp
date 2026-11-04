package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.action.Command
import org.tadams.nostromo.core.model.World

/** Applies player commands. A new order replaces whatever the actor was doing. */
internal object CommandSystem {
    /** Applies [commands] in the order they were submitted. */
    fun run(world: World, commands: List<Command>, scope: TickScope) {
        for (command in commands) {
            val actor = world.actors[command.actor] ?: continue
            ActionSystem.cancel(actor, scope)
            if (command is Command.Order) actor.queue.addAll(command.actions)
        }
    }
}
