package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkKind
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

/**
 * Trying to catch an animal here (docs/mechanics.md §9). Needs a catching item in use with nothing
 * in it already. Succeeds on a roll of 0-15 at least the crew member's difficulty, less the item's
 * bonus; the animal then rides in the item. On a miss it bolts to a neighbouring room at once.
 */
internal object CatchDef : ActionDef<Action.Catch> {
    override val type = ActionType.CATCH

    override fun candidates(actor: Actor, world: World) = world.occupants(actor.room)
        .filter { it.kind == ActorKind.ANIMAL && !it.isDown && it.caughtIn == null }
        .map { Action.Catch(it.id) }

    override fun check(actor: Actor, world: World, action: Action.Catch, rules: Rules): FailReason? {
        val target = world.actors.getValue(action.target)
        val item = world.inUse(actor)
        val canCatch = item != null && item.type.catchBonus != null && item.caught == null
        return when {
            target.isDown || target.caughtIn != null || target.room != actor.room -> FailReason.TARGET_NOT_HERE
            !canCatch -> FailReason.NEEDS_CATCHER
            else -> null
        }
    }

    override fun complete(actor: Actor, world: World, action: Action.Catch, scope: TickScope) {
        val target = world.actors.getValue(action.target)
        val item = world.inUse(actor) ?: return
        val needed = actor.catchDifficulty - (item.type.catchBonus ?: 0)
        val seenBy = Visibility.Perceived(world.witnesses(listOf(actor.room)))
        if (scope.roll(16, "${actor.name} catches ${target.name} (needs $needed+)") >= needed) {
            item.caught = target.id
            target.caughtIn = item.id
            target.current = null
            target.queue.clear()
            scope.emit(GameEvent.AnimalCaught(scope.tick, actor.id, target.id, item.id, seenBy))
            return
        }
        scope.emit(GameEvent.AnimalEscaped(scope.tick, actor.id, target.id, seenBy))
        val ways = world.room(target.room).exits.filter { it.kind == LinkKind.DOOR || it.kind == LinkKind.LADDER }
        if (ways.isEmpty()) return
        val from = target.room
        target.current = null
        target.queue.clear()
        target.room = ways[scope.roll(ways.size, "${target.name} bolts")].to
        scope.emit(GameEvent.ActorMoved(scope.tick, target.id, from, target.room, Visibility.Perceived(world.witnesses(listOf(from, target.room)))))
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
