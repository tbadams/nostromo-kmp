package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.Grilles
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules

/**
 * Removing the grille between a room and its duct, from either side (docs/mechanics.md §3, §6).
 * Once off it stays off; there's no putting it back, as in the original. Those on either side see
 * who did it; everyone else hears the grille sound ([org.tadams.nostromo.core.systems.DetectionSystem]).
 */
internal object RemoveGrilleDef : ActionDef<Action.RemoveGrille> {
    override val type = ActionType.REMOVE_GRILLE

    override fun candidates(actor: Actor, world: World) =
        if (Grilles.roomOf(actor.room, world) != null) listOf(Action.RemoveGrille) else emptyList()

    override fun check(actor: Actor, world: World, action: Action.RemoveGrille, rules: Rules): FailReason? {
        val room = Grilles.roomOf(actor.room, world) ?: return FailReason.NO_GRILLE
        return if (room.grilleRemoved) FailReason.GRILLE_ALREADY_REMOVED else null
    }

    override fun complete(actor: Actor, world: World, action: Action.RemoveGrille, scope: TickScope) {
        val room = Grilles.roomOf(actor.room, world) ?: return
        room.grilleRemoved = true
        val seenBy = Visibility.Perceived(world.witnesses(Grilles.sides(room)))
        scope.emit(GameEvent.GrilleRemoved(scope.tick, actor.id, room.id, seenBy))
    }
}
