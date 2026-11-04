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
) {
    /** Base duration of [type], before any per-actor adjustment. */
    fun baseSeconds(type: ActionType): Double = actionSeconds[type] ?: type.defaultSeconds
}
