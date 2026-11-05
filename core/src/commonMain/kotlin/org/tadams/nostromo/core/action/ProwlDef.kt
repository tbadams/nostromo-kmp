package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.TickScope

/**
 * Lingering in place. In the original the creature's random move could land on its own room (exit
 * tables were padded with the room itself); it stayed put, and if it was in a room rather than a
 * duct, the room took 5% damage (docs/mechanics.md §6).
 *
 * Differs from the original in one small way: damage lands when the prowl finishes, not when it
 * starts. TODO if more actions need effects at start, give ActionDef an optional onStart hook.
 */
internal object ProwlDef : ActionDef<Action.Prowl> {
    override val type = ActionType.PROWL

    override fun candidates(actor: Actor, world: World) = listOf(Action.Prowl)

    override fun check(actor: Actor, world: World, action: Action.Prowl, rules: Rules): FailReason? = null

    override fun complete(actor: Actor, world: World, action: Action.Prowl, scope: TickScope) {
        val room = world.room(actor.room)
        if (room.kind != RoomKind.ROOM || scope.rules.prowlDamagePercent == 0) return
        room.damage(scope.rules.prowlDamagePercent)
        // Crew in the room notice; nobody else does.
        val seenBy = Visibility.Perceived(world.witnesses(listOf(room.id)))
        scope.emit(GameEvent.RoomDamaged(scope.tick, room.id, room.damagePercent, seenBy))
    }
}
