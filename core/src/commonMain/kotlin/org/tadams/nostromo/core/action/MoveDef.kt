package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.Grilles
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.TickScope

/** Moving to an adjacent location: through a door, up a ladder, or into and along ducts. */
internal object MoveDef : ActionDef<Action.Move> {
    override val type = ActionType.MOVE

    override fun candidates(actor: Actor, world: World) =
        world.room(actor.room).exits.map { Action.Move(it.to) }

    override fun check(actor: Actor, world: World, action: Action.Move, rules: Rules): FailReason? {
        val exit = world.room(actor.room).exits.firstOrNull { it.to == action.to } ?: return FailReason.NOT_ADJACENT
        // TODO the original's creature goes through a grille faster than it walks (50 ticks vs 80);
        //  ours takes its normal move time. Crew take the same either way.
        val blocked = exit.kind == LinkKind.GRILLE && Grilles.roomOf(actor.room, world)?.grilleRemoved == false
        return if (blocked) FailReason.GRILLE_IN_PLACE else null
    }

    override fun complete(actor: Actor, world: World, action: Action.Move, scope: TickScope) {
        val from = actor.room
        actor.room = action.to
        val witnesses = world.witnesses(listOf(from, action.to))
        scope.emit(GameEvent.ActorMoved(scope.tick, actor.id, from, action.to, Visibility.Perceived(witnesses)))
    }
}
