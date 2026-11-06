package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.Grilles
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.systems.MoraleSystem

/**
 * Whether crew obey an order (docs/mechanics.md §5; the original's gate at `8EE3`). Unlike
 * [ActionDef.check], this isn't used to filter what's offered: the action bar still shows the order,
 * and the crew member refuses it or panics, as in the original.
 *
 * - [judge], when an action starts: panic at morale 0, refusal to attack or enter ducts below 2.
 * - [crowded], when a move lands: room capacity. A full destination makes the move wait, not fail.
 *
 * Crew left without orders are never checked, so a frightened crew member just stands there.
 * Getting, dropping and swapping items are never refused: they were instant menu choices in the
 * original, outside the gate, so a Broken crew member can still pick up a weapon to steady themselves.
 *
 * The android, before it's revealed, skips the morale check and never attacks the creature: an
 * order to do so becomes a move to a random neighboring room (docs/mechanics.md §8).
 *
 * Differs from the original: its gate ran when an action was about to land, ours when it starts, so
 * a crew member whose morale breaks mid-move still arrives. Capacity is checked on landing, as there.
 */
internal object ObedienceGate {

    private val NEVER_REFUSED = setOf(
        ActionType.GET, ActionType.DROP, ActionType.SWAP, ActionType.CATCH,
        ActionType.ARM_DESTRUCT, ActionType.DISARM_DESTRUCT, ActionType.BLOW_AIRLOCK, ActionType.SEAL_AIRLOCK,
        ActionType.HYPERSLEEP, ActionType.LAUNCH, ActionType.FIGHT_FIRE,
    )

    /** What a crew member does with an order. */
    sealed interface Verdict {
        data object Obey : Verdict

        data class Refuse(val reason: FailReason) : Verdict

        /** Ignore the order and run; see [panic]. */
        data object Panic : Verdict

        /** Quietly do [action] instead (null: nothing), dropping the rest of the order. The android's dodge. */
        data class Instead(val action: Action?) : Verdict
    }

    /** Whether [actor] will do [action], checked as it starts. Only loyal crew can refuse or panic. */
    fun judge(actor: Actor, world: World, action: Action, rules: Rules, scope: TickScope): Verdict {
        if (!actor.isLoyalCrew) return Verdict.Obey
        // Instant menu choices in the original, outside its gate: items, and the ship's controls.
        if (action.type in NEVER_REFUSED) return Verdict.Obey
        if (actor.isAndroid) {
            val target = (action as? Action.Attack)?.let { world.actors.getValue(it.target) }
            if (target?.kind != ActorKind.CREATURE) return Verdict.Obey
            val ways = world.room(actor.room).exits.filter { it.kind == LinkKind.DOOR || it.kind == LinkKind.LADDER }
            if (ways.isEmpty()) return Verdict.Instead(null)
            return Verdict.Instead(Action.Move(ways[scope.roll(ways.size, "${actor.name} (android) dodges the attack order")].to))
        }
        val morale = MoraleSystem.effective(actor, world, rules)
        if (morale <= rules.panicMorale) return Verdict.Panic
        val needsNerve = when (action) {
            is Action.Attack -> true
            // Into or along ducts; climbing out of one always works, so no one is trapped.
            is Action.Move -> world.room(action.to).kind == RoomKind.DUCT
            else -> false
        }
        return if (needsNerve && morale < rules.obedienceMorale) Verdict.Refuse(FailReason.TOO_SCARED) else Verdict.Obey
    }

    /**
     * [actor] panics instead of doing [ignored], and this returns where they run to (null: nowhere).
     * - In a room: they drop everything and run to a random neighboring room, by door or ladder.
     * - In a duct: they rip out its grille if it's in place, and climb out into its room.
     *
     * Differs from the original: its random pick could land on the room itself (its exit tables were
     * padded), so a panicking crew member sometimes stayed put. Ours always runs if there's a way.
     */
    fun panic(actor: Actor, world: World, ignored: Action, scope: TickScope): Action.Move? {
        val here = world.room(actor.room)
        scope.emit(GameEvent.Panicked(scope.tick, actor.id, ignored, Visibility.Perceived(world.witnesses(listOf(here.id)))))
        if (here.kind == RoomKind.DUCT) {
            val room = Grilles.roomOf(here.id, world) ?: return null
            if (!room.grilleRemoved) {
                room.grilleRemoved = true
                val seenBy = Visibility.Perceived(world.witnesses(Grilles.sides(room)))
                scope.emit(GameEvent.GrilleRemoved(scope.tick, actor.id, room.id, seenBy))
            }
            return Action.Move(room.id)
        }
        val seenBy = Visibility.Perceived(world.witnesses(listOf(here.id)))
        for (item in listOfNotNull(actor.inUse, actor.carried)) {
            world.drop(actor, item)
            scope.emit(GameEvent.ItemDropped(scope.tick, actor.id, item, seenBy))
        }
        val ways = here.exits.filter { it.kind == LinkKind.DOOR || it.kind == LinkKind.LADDER }
        if (ways.isEmpty()) return null
        return Action.Move(ways[scope.roll(ways.size, "${actor.name} panics")].to)
    }

    /**
     * Why [actor]'s [action] has to wait before it lands, or null if it can land now: a crew member
     * moving into a location already holding [Rules.roomCapacity] crew ([Rules.ductCapacity] for a
     * duct), bodies included. Only crew are limited, and only crew count; the creature goes anywhere.
     */
    fun crowded(actor: Actor, world: World, action: Action, rules: Rules): FailReason? {
        if (!actor.isLoyalCrew || action !is Action.Move) return null
        val destination = world.room(action.to)
        val limit = if (destination.kind == RoomKind.DUCT) rules.ductCapacity else rules.roomCapacity
        val there = world.occupants(destination.id).count { it.isCrew }
        return if (there >= limit) FailReason.ROOM_FULL else null
    }
}
