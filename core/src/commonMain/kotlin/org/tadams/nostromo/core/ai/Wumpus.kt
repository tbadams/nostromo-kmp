package org.tadams.nostromo.core.ai

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.systems.TickScope

/**
 * The original game's creature (docs/mechanics.md §6): it doesn't hunt. It attacks anyone standing
 * in its location, and otherwise wanders to a random neighbouring location, ducts included.
 *
 * Simplified for the vertical slice: no grille breaking, no carrying off bodies, no room damage
 * while idle, and every exit is equally likely (the original weights one of five slightly higher).
 */
internal object Wumpus : Brain {
    override fun decide(actor: Actor, world: World, scope: TickScope): Action? {
        val victims = world.occupants(actor.room).filter { it.isCrew && !it.isDown }
        if (victims.isNotEmpty()) {
            val victim = victims[scope.roll(victims.size, "${actor.name} picks a victim")]
            return Action.Attack(victim.id)
        }
        val exits = world.room(actor.room).exits
        if (exits.isEmpty()) return null
        return Action.Move(exits[scope.roll(exits.size, "${actor.name} picks an exit")].to)
    }
}
