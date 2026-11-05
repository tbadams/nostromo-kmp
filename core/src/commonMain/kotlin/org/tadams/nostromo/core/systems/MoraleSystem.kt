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
 *
 * Only loyal crew have morale or count as company; a revealed android is neither.
 */
internal object MoraleSystem {

    /**
     * Applies this tick's changes to base morale. Runs after [ActionSystem] and [BrainSystem], so it
     * can react to what they emitted in [TickScope.events].
     * - Crew lose [Rules.ductMorale] entering a duct and regain it leaving one.
     * - Crew who are up and share a location with an unfound body find it ("X finds Y's body"), and
     *   every crew member loses [Rules.bodyFoundMorale].
     * Driving the creature off ([Rules.fleeMorale]) is applied where it happens, in the attack.
     *
     * Base morale never goes below 0, so a crew member at 0 who enters and leaves a duct ends up
     * higher than they started, as in the original.
     *
     * Differs from the original: it looked for bodies only when someone arrived somewhere, so a crew
     * member who saw a collapse found the body only when someone next came or went. Ours finds it
     * straight away.
     */
    fun run(world: World, scope: TickScope) {
        for (moved in scope.events.filterIsInstance<GameEvent.ActorMoved>()) {
            val actor = world.actors.getValue(moved.actor)
            if (!actor.isLoyalCrew) continue
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
            val finder = crew.firstOrNull { it.isLoyalCrew && !it.isDown && it.room == body.room } ?: continue
            body.bodyFound = true
            val seenBy = Visibility.Perceived(world.witnesses(listOf(body.room)))
            scope.emit(GameEvent.BodyFound(scope.tick, finder.id, body.id, seenBy))
            crew.filter { it.isLoyalCrew && !it.isDown }.forEach { adjust(it, -scope.rules.bodyFoundMorale) }
        }
    }

    /** Changes [actor]'s own base morale by [by], kept between 0 and [cap]. */
    fun adjust(actor: Actor, by: Int, cap: Int = Int.MAX_VALUE) {
        actor.morale = (actor.morale + by).coerceIn(0, maxOf(cap, actor.morale))
    }

    /**
     * [actor]'s own morale plus the bonus of the item in use (or [Rules.caughtAnimalMorale] if it holds
     * a caught animal, like the original's "Cat in Box"). Never below 0.
     */
    fun base(actor: Actor, world: World, rules: Rules): Int {
        val item = world.inUse(actor)
        val bonus = when {
            item == null -> 0
            item.caught != null -> rules.caughtAnimalMorale
            else -> item.type.morale
        }
        return (actor.morale + bonus).coerceAtLeast(0)
    }

    /**
     * Effective morale, which decides obedience: base + Σ (base − [Rules.companyMorale]) for each
     * other loyal crew member here who is up − 1 per body here + [Rules.detectorClearMorale] if a
     * detector held here reads all clear ([DetectionSystem]). Never below 0.
     */
    fun effective(actor: Actor, world: World, rules: Rules): Int {
        val others = world.occupants(actor.room).filter { it.isCrew && it.id != actor.id }
        val company = others.filter { it.isLoyalCrew && !it.isDown }.sumOf { base(it, world, rules) - rules.companyMorale }
        val bodies = others.count { it.isDown }
        val clear = if (DetectionSystem.allClearIn(actor.room, world)) rules.detectorClearMorale else 0
        return (base(actor, world, rules) + company - bodies + clear).coerceAtLeast(0)
    }
}
