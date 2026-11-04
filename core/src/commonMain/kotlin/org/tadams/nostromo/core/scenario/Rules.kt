package org.tadams.nostromo.core.scenario

import kotlinx.serialization.Serializable
import org.tadams.nostromo.core.action.ActionType

/**
 * Tuning numbers. Defaults are the original game's values converted to seconds
 * (original ticks ÷ 13.5; see docs/mechanics.md). A scenario overrides only what it changes.
 */
@Serializable
data class Rules(
    /** Base duration of each action type. Types not listed use [ActionType.defaultSeconds]. */
    val actionSeconds: Map<ActionType, Double> = emptyMap(),
    /** Health lost per successful attack, until weapons arrive. Original: 1 for the creature and unarmed-ish crew. */
    val attackDamage: Int = 1,
    /** Room damage, in percent, each time the creature prowls in a room. Original: 5. */
    val prowlDamagePercent: Int = 5,
    /**
     * How strongly the creature prefers to linger rather than move on. Each exit has weight 1 and
     * prowling has weight `prowlWeight × (5 − exits)`, so dead ends make it linger like the
     * original's padded 5-slot exit tables did. 0 means it never lingers.
     */
    val prowlWeight: Int = 1,
) {
    /** Base duration of [type], before any per-actor adjustment. */
    fun baseSeconds(type: ActionType): Double = actionSeconds[type] ?: type.defaultSeconds
}
