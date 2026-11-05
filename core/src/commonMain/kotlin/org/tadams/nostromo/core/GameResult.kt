package org.tadams.nostromo.core

import org.tadams.nostromo.core.model.ActorId

/**
 * How a game ended: everything the endgame report needs (docs/mechanics.md §10).
 *
 * Several of these are independent of [ending]; e.g. the ship can explode with the creature
 * already dead, or the shuttle can launch without the self-destruct armed.
 */
data class GameResult(
    /** Why the game stopped. */
    val ending: Ending,
    /** Whether the creature is dead, however the game ended (the ship exploding kills it too). */
    val creatureKilled: Boolean,
    /** Whether the self-destruct was armed when the game ended. */
    val destructArmed: Boolean,
    /** Crew still alive at the end, whether or not they escaped. None if the ship exploded or the air ran out. */
    val survivors: List<ActorId>,
    /** Crew who got away on the shuttle. */
    val escaped: List<ActorId>,
    /** Crew who died or were lost; not those in hypersleep. */
    val casualties: List<ActorId>,
    /** Who the android was, revealed at the end. Null if there wasn't one. */
    val android: ActorId?,
    val rating: Rating,
)

/**
 * Why the game stopped. Only one applies; the rest of [GameResult] says what else was true. When
 * several happen on the same tick, the first listed here wins.
 */
enum class Ending {
    /** A room reached 100% damage, or the self-destruct went off. */
    SHIP_DESTROYED,

    /** The shuttle launched (a win only if the self-destruct was armed). */
    LAUNCHED,

    /** The oxygen ran out. */
    OXYGEN_OUT,

    /** Every crew member is down, in hypersleep or lost. */
    CREW_GONE,

    /** The creature died: in combat, or blown out of an airlock. */
    CREATURE_KILLED,
}

/**
 * The Competence Rating, in parts. Which parts count depends on the ending; parts that don't
 * count are 0. See docs/mechanics.md §10.
 */
data class Rating(
    /** CRW: 2 × health of each surviving crew member. */
    val crew: Int,
    /** DAM: 1 per room under 20% damage; only counts if the creature was killed. */
    val damage: Int = 0,
    /** TIM: 2 × the thousands digit of remaining oxygen. */
    val time: Int = 0,
) {
    val total: Int get() = (crew + damage + time).coerceAtMost(MAX)

    companion object {
        const val MAX = 100
    }
}
