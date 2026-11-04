package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules

/**
 * Morale (docs/mechanics.md §5): the rules for working it out, and for the events that change it.
 *
 * Morale is worked out on demand from the world rather than stored. The original updated effective
 * morale only when someone arrived somewhere; that differs only when base morale changes with nobody
 * moving (e.g. picking up an item next to a friend).
 */
internal object MoraleSystem {

    /**
     * Applies this tick's changes to base morale. Runs after [ActionSystem] and [BrainSystem], so it
     * can react to what they emitted in [TickScope.events].
     *
     * TODO base-morale changes, all on arrival (ActorMoved in scope.events), rest of build step 3:
     *  - −1 entering a duct, +1 leaving one.
     *  - Finding a body: crew arriving where an unfound body lies emits a BodyFound event
     *    ("X finds Y's body") and costs every crew member −1 (floor 0). Needs a "found" flag on down
     *    actors, which the map's hide-unseen-collapses TODO also needs.
     * TODO +1 to each attacker (max 8) for driving the creature off (build step 4, with scaring it off).
     */
    fun run(world: World, scope: TickScope) {
    }

    /** [actor]'s own morale plus the bonus of the item in use. Never below 0. */
    fun base(actor: Actor, world: World): Int =
        (actor.morale + (actor.inUse?.let { world.item(it).type.morale } ?: 0)).coerceAtLeast(0)

    /**
     * Effective morale, which decides obedience: base + Σ (base − [Rules.companyMorale]) for each
     * other crew member here who is up − 1 per body here. Never below 0.
     * TODO the tracker's "all clear" +1, with trackers (build step 4).
     */
    fun effective(actor: Actor, world: World, rules: Rules): Int {
        val others = world.occupants(actor.room).filter { it.isCrew && it.id != actor.id }
        val company = others.filterNot { it.isDown }.sumOf { base(it, world) - rules.companyMorale }
        val bodies = others.count { it.isDown }
        return (base(actor, world) + company - bodies).coerceAtLeast(0)
    }
}
