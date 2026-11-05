package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.RoomKind
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
     * - Crew lose [Rules.ductMorale] entering a duct and regain it leaving one.
     * - Crew who are up and share a location with an unfound body find it ("X finds Y's body"), and
     *   every crew member loses [Rules.bodyFoundMorale].
     *
     * Base morale never goes below 0, so a crew member at 0 who enters and leaves a duct ends up
     * higher than they started, as in the original.
     *
     * Differs from the original: it looked for bodies only when someone arrived somewhere, so a crew
     * member who saw a collapse found the body only when someone next came or went. Ours finds it
     * straight away.
     * TODO +1 to each attacker (max 8) for driving the creature off (build step 4, with scaring it off).
     */
    fun run(world: World, scope: TickScope) {
        for (moved in scope.events.filterIsInstance<GameEvent.ActorMoved>()) {
            val actor = world.actors.getValue(moved.actor)
            if (!actor.isCrew) continue
            val fromDuct = world.room(moved.from).kind == RoomKind.DUCT
            val toDuct = world.room(moved.to).kind == RoomKind.DUCT
            if (toDuct && !fromDuct) adjust(actor, -scope.rules.ductMorale)
            if (fromDuct && !toDuct) adjust(actor, scope.rules.ductMorale)
        }
        findBodies(world, scope)
    }

    private fun findBodies(world: World, scope: TickScope) {
        val crew = world.actors.values.filter { it.isCrew }
        for (body in crew.filter { it.isDown && !it.bodyFound }) {
            val finder = crew.firstOrNull { !it.isDown && it.room == body.room } ?: continue
            body.bodyFound = true
            val seenBy = Visibility.Perceived(world.witnesses(listOf(body.room)))
            scope.emit(GameEvent.BodyFound(scope.tick, finder.id, body.id, seenBy))
            crew.filterNot { it.isDown }.forEach { adjust(it, -scope.rules.bodyFoundMorale) }
        }
    }

    private fun adjust(actor: Actor, by: Int) {
        actor.morale = (actor.morale + by).coerceAtLeast(0)
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
