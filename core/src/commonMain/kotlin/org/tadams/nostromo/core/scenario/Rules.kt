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
    /**
     * Action types an actor's speed penalty isn't added to. Original: picking up, dropping and
     * swapping items were instant for everyone.
     */
    val unslowedActions: Set<ActionType> = setOf(ActionType.GET, ActionType.DROP, ActionType.SWAP),
    /**
     * Health lost per attack by a non-crew attacker with no item in use, e.g. the creature. Original: 1.
     * Crew do only what their in-use item does ([ItemTypeDef.damage]); bare-handed, nothing.
     */
    val attackDamage: Int = 1,
    /** Room damage, in percent, each time the creature prowls in a room. Original: 5. */
    val prowlDamagePercent: Int = 5,
    /**
     * How strongly the creature prefers to linger rather than move on. Each exit has weight 1 and
     * prowling has weight `prowlWeight × (5 − exits)`, so dead ends make it linger like the
     * original's padded 5-slot exit tables did. 0 means it never lingers.
     */
    val prowlWeight: Int = 1,
    /** Chance in 16 that the creature, finding a grille in place, sets about removing it. Original: 3. */
    val creatureGrilleOdds: Int = 3,
    /**
     * Each other crew member here who is up adds (their base morale − this) to effective morale, so
     * brave company lifts it and frightened company drags it down. Original: 2.
     */
    val companyMorale: Int = 2,
    /** Crew whose effective morale is below this refuse to attack or to go into ducts. Original: 2. */
    val obedienceMorale: Int = 2,
) {
    /** Base duration of [type], before any per-actor adjustment. */
    fun baseSeconds(type: ActionType): Double = actionSeconds[type] ?: type.defaultSeconds
}
