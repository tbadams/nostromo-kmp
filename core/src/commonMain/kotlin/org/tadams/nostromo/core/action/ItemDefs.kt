package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.TickScope

/*
 * Items (docs/mechanics.md §4): each crew member has one item in use and one carried spare. Only the
 * one in use counts, as a weapon and for morale. Getting, dropping and swapping are instant, as in
 * the original.
 */

/** Picking up an item lying here. */
internal object GetDef : ActionDef<Action.Get> {
    override val type = ActionType.GET

    override fun candidates(actor: Actor, world: World) = world.itemsIn(actor.room).map { Action.Get(it.id) }

    override fun check(actor: Actor, world: World, action: Action.Get, rules: Rules): FailReason? = when {
        world.item(action.item).room != actor.room -> FailReason.ITEM_NOT_HERE
        actor.inUse != null && actor.carried != null -> FailReason.HANDS_FULL
        else -> null
    }

    override fun complete(actor: Actor, world: World, action: Action.Get, scope: TickScope) {
        val item = world.item(action.item)
        item.room = null
        if (actor.inUse == null) actor.inUse = item.id else actor.carried = item.id
        scope.emit(GameEvent.ItemTaken(scope.tick, actor.id, item.id, Visibility.Perceived(world.witnesses(listOf(actor.room)))))
    }
}

/** Putting down a held item. */
internal object DropDef : ActionDef<Action.Drop> {
    override val type = ActionType.DROP

    override fun candidates(actor: Actor, world: World) = listOfNotNull(actor.inUse, actor.carried).map { Action.Drop(it) }

    override fun check(actor: Actor, world: World, action: Action.Drop, rules: Rules): FailReason? =
        if (action.item != actor.inUse && action.item != actor.carried) FailReason.NOT_HOLDING else null

    override fun complete(actor: Actor, world: World, action: Action.Drop, scope: TickScope) {
        // Seen before it's dropped, so the one leaving it counts even if that changes later.
        val seenBy = Visibility.Perceived(world.witnesses(listOf(actor.room)))
        world.drop(actor, action.item)
        scope.emit(GameEvent.ItemDropped(scope.tick, actor.id, action.item, seenBy))
    }
}

/** Putting the carried item into use; whatever was in use becomes the carried one. */
internal object SwapDef : ActionDef<Action.Swap> {
    override val type = ActionType.SWAP

    override fun candidates(actor: Actor, world: World) = listOfNotNull(actor.carried).map { Action.Swap(it) }

    override fun check(actor: Actor, world: World, action: Action.Swap, rules: Rules): FailReason? =
        if (action.item != actor.carried) FailReason.NOT_HOLDING else null

    override fun complete(actor: Actor, world: World, action: Action.Swap, scope: TickScope) {
        actor.carried = actor.inUse
        actor.inUse = action.item
        scope.emit(GameEvent.ItemSwapped(scope.tick, actor.id, action.item, Visibility.Perceived(world.witnesses(listOf(actor.room)))))
    }
}
