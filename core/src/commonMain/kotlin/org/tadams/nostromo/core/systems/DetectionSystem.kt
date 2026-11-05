package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.NoiseKind
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.Item
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.RoomKind

/**
 * Clues (docs/mechanics.md §9): ship-wide sounds, and detectors.
 *
 * - **Sounds.** Anyone entering a room (not a duct) makes a door sound; removing a grille makes a
 *   grille sound. Both are heard everywhere, as [GameEvent.Noise] with [Visibility.Ship].
 * - **Detectors.** A tracker in use, or a caught animal in its box or net, scans the holder's
 *   neighbouring locations (not their own) for anything alive: crew, the creature, the cat. So it's
 *   a motion detector, not a creature detector. A reading is reported to the holder when it changes,
 *   and an all-clear gives crew in the holder's location [org.tadams.nostromo.core.scenario.Rules.detectorClearMorale].
 *
 * Differs from the original: its tracker beeped only for the selected crew member; ours reports
 * each holder's reading, and the UI or narrator decides who to tell.
 */
internal object DetectionSystem {

    /** Emits this tick's sounds and detector changes. [readings] is the last reading reported per detector, kept by the caller. */
    fun run(world: World, scope: TickScope, readings: MutableMap<ItemId, Boolean>) {
        for (event in scope.events.toList()) {
            when (event) {
                is GameEvent.ActorMoved -> if (world.room(event.to).kind == RoomKind.ROOM) {
                    scope.emit(GameEvent.Noise(scope.tick, NoiseKind.DOOR, event.to, event.actor))
                }
                is GameEvent.GrilleRemoved -> scope.emit(GameEvent.Noise(scope.tick, NoiseKind.GRILLE, event.room, event.actor))
                else -> {}
            }
        }
        for (holder in world.actors.values) {
            if (holder.outOfPlay || !holder.isLoyalCrew) continue
            val item = detector(holder, world) ?: continue
            val movement = movementNear(holder, world)
            if (readings[item.id] == movement) continue
            readings[item.id] = movement
            scope.emit(GameEvent.DetectorReading(scope.tick, holder.id, item.id, movement, Visibility.Perceived(setOf(holder.id))))
        }
        // Forget readings for detectors no longer in use, so picking one up again reports afresh.
        val inUse = world.actors.values.filter { !it.outOfPlay }.mapNotNull { detector(it, world)?.id }.toSet()
        readings.keys.retainAll(inUse)
    }

    /** Whether a detector held by someone up in [room] reads all clear right now. */
    fun allClearIn(room: RoomId, world: World): Boolean =
        world.occupants(room).any { !it.isDown && it.isLoyalCrew && detector(it, world) != null && !movementNear(it, world) }

    /** [holder]'s in-use detector: a tracker, or an item with an animal in it. */
    private fun detector(holder: Actor, world: World): Item? =
        world.inUse(holder)?.takeIf { it.type.detects || it.caught != null }

    /** Anything alive and loose in a location next to [holder]'s, not counting [holder]'s own. */
    private fun movementNear(holder: Actor, world: World): Boolean {
        val near = world.room(holder.room).exits.map { it.to }.toSet() - holder.room
        return world.actors.values.any { !it.outOfPlay && it.caughtIn == null && it.room in near }
    }
}
