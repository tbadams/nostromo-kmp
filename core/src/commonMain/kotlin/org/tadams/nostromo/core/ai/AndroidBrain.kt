package org.tadams.nostromo.core.ai

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.RoomKind

/**
 * The revealed android (docs/mechanics.md §8): it attacks a loyal crew member who's up in its room,
 * and otherwise wanders between rooms. It never uses ducts and never attacks the creature.
 */
internal object AndroidBrain : Brain {
    override fun decide(actor: Actor, options: List<Action>, world: World, scope: TickScope): Action? {
        val attacks = options.filterIsInstance<Action.Attack>().filter { world.actors.getValue(it.target).isLoyalCrew }
        if (attacks.isNotEmpty()) return attacks.first()
        val moves = options.filterIsInstance<Action.Move>().filter { world.room(it.to).kind == RoomKind.ROOM }
        if (moves.isEmpty()) return null
        return moves[scope.roll(moves.size, "${actor.name} (android) wanders")]
    }
}

/**
 * The cat (docs/mechanics.md §9): every so often it wanders to a neighbouring room, never into
 * ducts. The pause between moves is the scenario's move time for the cat (the original: 255 ticks).
 *
 * Differs from the original: there, landing with the creature made it move again at once; ours
 * avoids rooms where the creature is when it picks where to go, which has much the same effect.
 */
internal object CatBrain : Brain {
    override fun decide(actor: Actor, options: List<Action>, world: World, scope: TickScope): Action? {
        val rooms = options.filterIsInstance<Action.Move>().filter { world.room(it.to).kind == RoomKind.ROOM }
        val safe = rooms.filter { move -> world.occupants(move.to).none { it.kind == ActorKind.CREATURE && !it.isDown } }
        val moves = safe.ifEmpty { rooms }
        if (moves.isEmpty()) return null
        return moves[scope.roll(moves.size, "${actor.name} wanders")]
    }
}
