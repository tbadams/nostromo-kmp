package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.event.WarningKind
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.RoomFeature
import org.tadams.nostromo.core.scenario.RoomKind

/**
 * Room damage, fire and oxygen (docs/mechanics.md §3, §10). Runs after actions and brains, so it sees
 * this tick's damage.
 *
 * - **Damage thresholds**, each warned of once per room: at [org.tadams.nostromo.core.scenario.Rules.fireAtPercent]
 *   a flammable room catches fire and a key system fails; at `structuralAtPercent` a structural
 *   warning. Any room at 100% blows up the ship ([EndSystem] ends the game).
 * - **Fire** adds `fireDamagePercent` every `fireIntervalSeconds` until fought out. Fire never spreads,
 *   as in the original. A room can catch fire again later; the fight count starts afresh.
 * - **Oxygen**: each crew member breathing (loyal, in play) uses `oxygenPerCrewSecond`. A warning at
 *   `oxygenCriticalAt`; at 0 the game ends.
 */
internal object HazardSystem {

    fun run(world: World, scope: TickScope) {
        val rules = scope.rules
        for (room in world.rooms.values) {
            if (room.kind != RoomKind.ROOM) continue
            if (room.burning) {
                room.fireTimer--
                if (room.fireTimer <= 0) {
                    room.fireTimer = Time.ticks(rules.fireIntervalSeconds)
                    room.damage(rules.fireDamagePercent)
                    scope.emit(GameEvent.RoomDamaged(scope.tick, room.id, room.damagePercent, Visibility.Perceived(world.witnesses(listOf(room.id)))))
                }
            }
            if (room.damagePercent >= rules.fireAtPercent && rules.fireAtPercent !in room.warned) {
                room.warned += rules.fireAtPercent
                if (RoomFeature.FLAMMABLE in room.features) {
                    room.burning = true
                    room.fireFights = 0
                    room.fireTimer = Time.ticks(rules.fireIntervalSeconds)
                    scope.emit(GameEvent.FireStarted(scope.tick, room.id))
                }
                if (RoomFeature.KEY_SYSTEM in room.features) scope.emit(GameEvent.DamageWarning(scope.tick, room.id, WarningKind.SYSTEM_FAILURE))
            }
            if (room.damagePercent >= rules.structuralAtPercent && rules.structuralAtPercent !in room.warned) {
                room.warned += rules.structuralAtPercent
                scope.emit(GameEvent.DamageWarning(scope.tick, room.id, WarningKind.STRUCTURAL))
            }
        }

        val ship = world.ship
        val breathing = world.actors.values.count { it.isLoyalCrew && !it.outOfPlay }
        ship.oxygen = (ship.oxygen - breathing * rules.oxygenPerCrewSecond / Time.TICKS_PER_SECOND).coerceAtLeast(0.0)
        if (!ship.oxygenWarned && ship.oxygen <= rules.oxygenCriticalAt) {
            ship.oxygenWarned = true
            scope.emit(GameEvent.OxygenCritical(scope.tick))
        }
    }

    /** Whether any room has reached 100% damage. */
    fun shipDestroyed(world: World) = world.rooms.values.any { it.damagePercent >= 100 }
}

/** The self-destruct clock (docs/mechanics.md §10). Arming and overriding are actions ([org.tadams.nostromo.core.action.ArmDestructDef]). */
internal object ShipSystem {
    fun run(world: World) {
        val left = world.ship.destructTicksLeft ?: return
        world.ship.destructTicksLeft = (left - 1).coerceAtLeast(0)
    }
}
