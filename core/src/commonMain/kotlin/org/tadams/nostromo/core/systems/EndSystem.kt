package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.Ending
import org.tadams.nostromo.core.GameResult
import org.tadams.nostromo.core.Rating
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind

/** Checks whether the game is over, and if so works out the [GameResult]. Runs last each tick. */
internal object EndSystem {
    /** The result if the game ended this tick, else null. Emits [GameEvent.GameOver] when it does. */
    fun run(world: World, scope: TickScope): GameResult? {
        val actors = world.actors.values
        // A revealed android isn't on the crew's side, so the game doesn't wait on it.
        val crew = actors.filter { it.isLoyalCrew }
        val creatures = actors.filter { it.kind == ActorKind.CREATURE }
        val creatureKilled = creatures.isNotEmpty() && creatures.all { it.isDown }
        val crewGone = crew.all { it.isDown }

        // If both happen on the same tick, the crew going down is what ended it; creatureKilled
        // still records the kill.
        val ending = when {
            crewGone -> Ending.CREW_GONE
            creatureKilled -> Ending.CREATURE_KILLED
            else -> return null
        }
        val survivors = crew.filter { !it.isDown }
        val result = GameResult(
            ending = ending,
            creatureKilled = creatureKilled,
            destructArmed = false,
            survivors = survivors.map { it.id },
            escaped = emptyList(),
            casualties = crew.filter { it.isDown }.map { it.id },
            android = actors.firstOrNull { it.isAndroid }?.id,
            // The android, revealed or not, doesn't count towards the crew's rating (docs/mechanics.md §10).
            rating = Rating(crew = survivors.filterNot { it.isAndroid }.sumOf { 2 * it.health }),
        )
        scope.emit(GameEvent.GameOver(scope.tick, result))
        return result
    }
}
