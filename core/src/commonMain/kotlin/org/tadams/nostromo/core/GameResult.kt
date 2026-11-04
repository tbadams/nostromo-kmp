package org.tadams.nostromo.core

import org.tadams.nostromo.core.model.ActorId

// TODO placeholder, finalize
/**
 * How a game ended: everything the endgame report needs (docs/mechanics.md §10).
 *
 * Several of these are independent of [ending]; e.g. the ship can explode with the creature
 * already dead, or the shuttle can launch without the self-destruct armed.
 */
data class GameResult(
    /** Why the game stopped. */
    val ending: Ending,
    /** Whether the creature is dead, however the game ended. */
    val creatureKilled: Boolean,
    /** Whether the self-destruct was armed when the game ended. Always false until it exists. */
    val destructArmed: Boolean,
    /** Crew still up at the end, whether or not they escaped. */
    val survivors: List<ActorId>,
    /** Crew who got away on the shuttle. Empty until launching exists. */
    val escaped: List<ActorId>,
    /** Crew who went down. */
    val casualties: List<ActorId>,
    /** Who the android was, revealed at the end. Null if there wasn't one. */
    val android: ActorId?,
    val rating: Rating,
)

/** Why the game stopped. Only one applies; the rest of [GameResult] says what else was true. */
enum class Ending {
    /** The creature took enough damage to die. */
    CREATURE_KILLED,

    /** Every crew member is down (later: or in hypersleep, or otherwise gone). */
    CREW_GONE,
    // Planned (build step 5): LAUNCHED, OXYGEN_OUT, SHIP_DESTROYED.
}

/**
 * The Competence Rating, in parts. Which parts count depends on the ending; parts that don't
 * count are 0. See docs/mechanics.md §10.
 */
data class Rating(
    /** 2 × health of each surviving crew member. */
    val crew: Int,
    /** 1 per room under 20% damage; only counts if the creature was killed. 0 until room damage exists. */
    val damage: Int = 0,
    /** 2 × the thousands digit of remaining oxygen. 0 until oxygen exists. */
    val time: Int = 0,
) {
    val total: Int get() = (crew + damage + time).coerceAtMost(MAX)

    companion object {
        const val MAX = 100
    }
}
