package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.systems.TickScope

/** Moving to an adjacent location: through a door, up a ladder, or into and along ducts. */
internal object MoveDef : ActionDef<Action.Move> {
    override val type = ActionType.MOVE

    override fun candidates(actor: Actor, world: World) =
        world.room(actor.room).exits.map { Action.Move(it.to) }

    override fun check(actor: Actor, world: World, action: Action.Move): FailReason? =
        if (world.room(actor.room).exits.none { it.to == action.to }) FailReason.NOT_ADJACENT else null

    override fun complete(actor: Actor, world: World, action: Action.Move, scope: TickScope) {
        val from = actor.room
        actor.room = action.to
        val witnesses = world.witnesses(listOf(from, action.to))
        scope.emit(GameEvent.ActorMoved(scope.tick, actor.id, from, action.to, Visibility.Perceived(witnesses)))
    }
}
