package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.Ending
import org.tadams.nostromo.core.GameResult
import org.tadams.nostromo.core.Rating
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.RoomKind

/**
 * Checks whether the game is over, and if so works out the [GameResult] and Competence Rating
 * (docs/mechanics.md §10). Runs last each tick.
 *
 * Rating parts: CRW = 2 × health of each surviving loyal crew member (not the android); DAM = 1 per
 * room under [org.tadams.nostromo.core.scenario.Rules.undamagedBelowPercent]; TIM = 2 × the thousands
 * digit of the oxygen left. Which parts count depends on the ending:
 *
 * | ending | rating |
 * |---|---|
 * | creature killed | CRW + DAM + TIM |
 * | launched, destruct armed | CRW + TIM |
 * | launched, not armed | 0 |
 * | crew gone (down, asleep, lost) | CRW + TIM if armed, else 0 |
 * | oxygen out | TIM if armed, else 0 |
 * | ship destroyed (100% damage or the destruct) | TIM |
 *
 * When several happen on one tick, the first in [Ending] order that applies is the ending.
 */
internal object EndSystem {
    /** The result if the game ended this tick, else null. Emits [GameEvent.GameOver] when it does. */
    fun run(world: World, scope: TickScope): GameResult? {
        val actors = world.actors.values
        val ship = world.ship
        // A revealed android isn't on the crew's side, so the game doesn't wait on it.
        val crew = actors.filter { it.isLoyalCrew }
        val creatures = actors.filter { it.kind == ActorKind.CREATURE }
        val creatureKilled = creatures.isNotEmpty() && creatures.all { it.isDown }
        val alive = crew.filter { !it.outOfPlay }

        val ending = when {
            HazardSystem.shipDestroyed(world) || ship.destructTicksLeft == 0L -> Ending.SHIP_DESTROYED
            ship.launched -> Ending.LAUNCHED
            ship.oxygen <= 0.0 -> Ending.OXYGEN_OUT
            alive.isEmpty() -> Ending.CREW_GONE
            creatureKilled -> Ending.CREATURE_KILLED
            else -> return null
        }

        // In the original, the ship exploding or the oxygen running out marked the whole crew dead.
        val survivors = when (ending) {
            Ending.SHIP_DESTROYED, Ending.OXYGEN_OUT -> emptyList()
            else -> alive
        }
        val armed = ship.destructArmed
        val crw = survivors.filterNot { it.isAndroid }.sumOf { 2 * it.health }
        val dam = world.rooms.values.count { it.kind == RoomKind.ROOM && it.damagePercent < scope.rules.undamagedBelowPercent }
        val tim = 2 * ((ship.oxygen.toInt() / 1000) % 10)
        val rating = when (ending) {
            Ending.CREATURE_KILLED -> Rating(crw, dam, tim)
            Ending.LAUNCHED -> if (armed) Rating(crw, 0, tim) else Rating(0)
            Ending.CREW_GONE -> if (armed) Rating(crw, 0, tim) else Rating(0)
            Ending.OXYGEN_OUT -> if (armed) Rating(0, 0, tim) else Rating(0)
            Ending.SHIP_DESTROYED -> Rating(0, 0, tim)
        }
        val result = GameResult(
            ending = ending,
            creatureKilled = creatureKilled || (ending == Ending.SHIP_DESTROYED),
            destructArmed = armed,
            survivors = survivors.map { it.id },
            escaped = if (ending == Ending.LAUNCHED) alive.map { it.id } else emptyList(),
            casualties = crew.filter { it !in survivors && !it.asleep }.map { it.id },
            android = actors.firstOrNull { it.isAndroid }?.id,
            rating = rating,
        )
        scope.emit(GameEvent.GameOver(scope.tick, result))
        return result
    }
}
