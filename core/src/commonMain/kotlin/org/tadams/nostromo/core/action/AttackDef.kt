package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.TickScope

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
        val damage = damage(actor, world, scope)
        target.health = (target.health - damage).coerceAtLeast(0)
        scope.emit(GameEvent.AttackHit(scope.tick, actor.id, target.id, damage, target.health, seenBy))
        if (target.isDown) {
            // Going down stops whatever they were doing, and they never act again. What they held drops.
            target.current = null
            target.queue.clear()
            listOfNotNull(target.inUse, target.carried).forEach { world.drop(target, it) }
            scope.emit(GameEvent.ActorDown(scope.tick, target.id, seenBy))
        }
    }

    /**
     * Health one hit takes off: what the item in use does (docs/mechanics.md §7). Crew with nothing in
     * use do nothing, as in the original; other attackers (the creature) do [Rules.attackDamage].
     * TODO charges and special effects per item (build step 4; see ItemTypeDef).
     */
    private fun damage(actor: Actor, world: World, scope: TickScope): Int {
        val item = actor.inUse?.let(world::item)
        return when {
            item != null -> item.type.damage
            actor.isCrew -> 0
            else -> scope.rules.attackDamage
        }
    }
}
