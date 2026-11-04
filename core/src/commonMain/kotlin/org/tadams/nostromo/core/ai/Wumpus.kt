package org.tadams.nostromo.core.ai

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.TickScope

/**
 * The original game's creature (docs/mechanics.md §6): it doesn't hunt. It attacks crew standing
 * in its location. Otherwise it either wanders to a random neighbouring location, ducts included,
 * or lingers where it is (prowls), damaging the room. Dead ends make it linger more.
 *
 * Grilles in place block it like anyone else; finding one, it sets about removing it with
 * [org.tadams.nostromo.core.scenario.Rules.creatureGrilleOdds] in 16, as in the original.
 *
 * Simplified: no carrying off bodies. The linger odds approximate the original's padded 5-slot exit
 * tables rather than copying them per room.
 * TODO the original's 6/16 chance to go through an open grille before considering other exits;
 *  ours treats the way through as one exit among the rest.
 */
internal object Wumpus : Brain {
    /** The original's exit tables had 5 slots; unused slots meant "stay". */
    private const val SLOTS = 5

    override fun decide(actor: Actor, options: List<Action>, world: World, scope: TickScope): Action? {
        val attacks = options.filterIsInstance<Action.Attack>().filter { world.actors.getValue(it.target).isCrew }
        if (attacks.isNotEmpty()) return attacks[scope.roll(attacks.size, "${actor.name} picks a victim")]

        if (Action.RemoveGrille in options &&
            scope.roll(16, "${actor.name} tries the grille") < scope.rules.creatureGrilleOdds
        ) {
            return Action.RemoveGrille
        }

        val moves = options.filterIsInstance<Action.Move>()
        val prowlWeight = if (Action.Prowl in options) scope.rules.prowlWeight * (SLOTS - moves.size).coerceAtLeast(0) else 0
        val weighted = moves.map { it to 1 } + listOf(Action.Prowl to prowlWeight).filter { it.second > 0 }
        if (weighted.isEmpty()) return null
        return scope.pickWeighted(weighted, "${actor.name} wanders or lingers")
    }
}
