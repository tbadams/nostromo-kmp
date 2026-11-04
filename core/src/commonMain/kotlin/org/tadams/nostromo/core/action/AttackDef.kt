package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.systems.TickScope

/** Striking someone in the same location. */
internal object AttackDef : ActionDef<Action.Attack> {
    override val type = ActionType.ATTACK

    /** Anyone else here who is still up. Whether an actor *wants* to hit them is the chooser's business. */
    override fun candidates(actor: Actor, world: World) =
        world.occupants(actor.room).filter { it.id != actor.id && !it.isDown }.map { Action.Attack(it.id) }

    override fun check(actor: Actor, world: World, action: Action.Attack): FailReason? {
        val target = world.actors.getValue(action.target)
        return when {
            target.isDown -> FailReason.TARGET_DOWN
            target.room != actor.room -> FailReason.TARGET_NOT_HERE
            else -> null
        }
    }

    override fun complete(actor: Actor, world: World, action: Action.Attack, scope: TickScope) {
        val target = world.actors.getValue(action.target)
        // Every hit is reported; the log narrator in :shared (game/Narrator) folds a fight into a line or two.
        // Work out who saw it before the hit lands, so a crew member who goes down still saw it.
        val seenBy = Visibility.Perceived(world.witnesses(listOf(actor.room)))
        target.health = (target.health - scope.rules.attackDamage).coerceAtLeast(0)
        scope.emit(GameEvent.AttackHit(scope.tick, actor.id, target.id, target.health, seenBy))
        if (target.isDown) {
            // Going down stops whatever they were doing, and they never act again.
            target.current = null
            target.queue.clear()
            scope.emit(GameEvent.ActorDown(scope.tick, target.id, seenBy))
        }
    }
}
