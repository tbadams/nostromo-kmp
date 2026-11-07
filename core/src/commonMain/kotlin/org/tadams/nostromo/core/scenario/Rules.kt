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
     * Action types an actor's speed and wound penalties aren't added to. Original: picking up,
     * dropping and swapping items, and grabbing the cat, were instant for everyone.
     */
    val unslowedActions: Set<ActionType> = setOf(
        ActionType.GET, ActionType.DROP, ActionType.SWAP, ActionType.CATCH,
        ActionType.ARM_DESTRUCT, ActionType.DISARM_DESTRUCT, ActionType.BLOW_AIRLOCK, ActionType.SEAL_AIRLOCK,
        ActionType.HYPERSLEEP, ActionType.LAUNCH, ActionType.FIGHT_FIRE,
    ),
    /**
     * Extra seconds on a crew member's actions when they're wounded, by health left. Original: +16
     * ticks at its health 3 and +48 at 2, which is our 2 and 1 (our health is the original's minus 1).
     */
    val woundPenaltySeconds: Map<Int, Double> = mapOf(2 to 1.2, 1 to 3.6),
    /**
     * Health lost per attack by a non-crew attacker, e.g. the creature or the revealed android. Original: 1.
     * Crew do only what their in-use item does ([ItemTypeDef.damage]); bare-handed, nothing.
     */
    val attackDamage: Int = 1,
    /** Whether crew can attack each other. Original: no; crew could only attack the creature (and the revealed android). */
    val friendlyFire: Boolean = false,
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
    /** Chance in 16 that the creature, finding a grille open, goes through it before considering other exits. Original: 6. */
    val creatureGrilleMoveOdds: Int = 6,
    /**
     * Each other crew member here who is up adds (their base morale − this) to effective morale, so
     * brave company lifts it and frightened company drags it down. Original: 2.
     */
    val companyMorale: Int = 2,
    /** Crew whose effective morale is below this refuse to attack or to go into ducts. Original: 2. */
    val obedienceMorale: Int = 2,
    /** Crew whose effective morale is at or below this panic when given an order. Original: 0. */
    val panicMorale: Int = 0,
    /** Base morale lost entering a duct, and regained leaving one. Original: 1. */
    val ductMorale: Int = 1,
    /** Base morale every crew member loses when anyone finds a body. Original: 1. */
    val bodyFoundMorale: Int = 1,
    /** Most crew (bodies included) a room can hold; a move into a full one waits. Original: 3. */
    val roomCapacity: Int = 3,
    /** Most crew a duct segment can hold. Original: 1. */
    val ductCapacity: Int = 1,
    /** How long a move into a full location waits before trying again, plus the speed penalty. Original: 32 ticks. */
    val crowdedWaitSeconds: Double = 2.4,
    /**
     * Driving the creature off (docs/mechanics.md §7). After a hit that doesn't kill it, each crew
     * member attacking it who is healthy (health above [fleeHealthAbove]) adds 1 if their effective
     * morale is at least [fleeConfidentMorale], plus the creature's damage ÷ [fleeDamageDivisor].
     * It flees with that many chances in 16, never on a roll of 15. Original: above 2 of its health
     * (our 1), 4, 4.
     */
    val fleeHealthAbove: Int = 1,
    val fleeConfidentMorale: Int = 4,
    val fleeDamageDivisor: Int = 4,
    /** Base morale each attacker gains for driving the creature off, up to [moraleCap]. Original: 1, max 8. */
    val fleeMorale: Int = 1,
    val moraleCap: Int = 8,
    /** How long the net freezes its target. Original: 255 ticks. */
    val netFreezeSeconds: Double = 18.9,
    /** Damage the creature has taken when the android reveals itself. Original: 6. */
    val androidRevealDamage: Int = 6,
    /** Effective morale crew gain while a detector in their location reads all clear. Original: 1. */
    val detectorClearMorale: Int = 1,
    /** Base morale added by an item in use with a caught animal in it. Original: 1 ("Cat in Box"). */
    val caughtAnimalMorale: Int = 1,

    // ---- The ship (docs/mechanics.md §3, §10)

    /**
     * How long the self-destruct runs once armed, in real seconds. Override stops it, and arming again
     * starts from the top. Original: shown as 5:00 but about 7.4 real minutes; ours is 7:00.
     */
    val destructSeconds: Double = 420.0,
    /** Oxygen at the start, in the original's units. The thousands digit scores TIM. Original: 7500. */
    val oxygenStart: Int = 7500,
    /**
     * Whether crew use oxygen at all. Off, it stays at [oxygenStart] all game: it never warns and
     * never runs out. Original: on.
     */
    val oxygenUsed: Boolean = true,
    /**
     * Oxygen each breathing crew member (up, loyal, not in hypersleep) uses per second. Differs from
     * the original, whose rate was fastest with exactly one crew member missing (a bug). 1.13 gives a
     * crew of six about the original long game's 18.5 minutes, and longer as crew are lost.
     */
    val oxygenPerCrewSecond: Double = 1.13,
    /** Oxygen level at which the "critical" warning sounds. Original: 500. */
    val oxygenCriticalAt: Int = 500,
    /** Room damage at which flammable rooms catch fire and key systems fail. Original: 30. */
    val fireAtPercent: Int = 30,
    /** Room damage at which the "structural damage" warning sounds. Original: 70. At 100 the ship explodes. */
    val structuralAtPercent: Int = 70,
    /** Damage a burning room takes every [fireIntervalSeconds]. Original: 5% every 100 ticks. */
    val fireDamagePercent: Int = 5,
    val fireIntervalSeconds: Double = 7.4,
    /**
     * Uses of an extinguisher needed to put a fire out: 1 below 60% damage, 2 below 80%, 3 above.
     * The count resets once it's out (the original never reset it, a bug).
     */
    val fireFightsNeeded: List<FireFights> = listOf(FireFights(60, 1), FireFights(80, 2), FireFights(101, 3)),
    /** Rooms below this damage score 1 each for DAM in the Competence Rating. Original: 20. */
    val undamagedBelowPercent: Int = 20,
    /** A creature blown out of an airlock with at least this much damage always dies. Original: 3. */
    val airlockKillDamage: Int = 3,
    /** Otherwise, its chance in 16 of surviving and coming back in at the intruder entry. Original: 2. */
    val airlockSurvivalOdds: Int = 2,
) {
    /** Extinguisher uses needed for a fire in a room at [damagePercent]. */
    fun fireFightsFor(damagePercent: Int): Int =
        fireFightsNeeded.firstOrNull { damagePercent < it.belowPercent }?.uses ?: fireFightsNeeded.lastOrNull()?.uses ?: 1

    /** Base duration of [type], before any per-actor adjustment. */
    fun baseSeconds(type: ActionType): Double = actionSeconds[type] ?: type.defaultSeconds
}

/** A fire in a room below [belowPercent] damage needs [uses] extinguisher uses. */
@Serializable
data class FireFights(val belowPercent: Int, val uses: Int)
