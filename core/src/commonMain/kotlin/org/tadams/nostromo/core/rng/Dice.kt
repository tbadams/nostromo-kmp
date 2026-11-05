package org.tadams.nostromo.core.rng

import kotlin.random.Random

/**
 * The size of the original game's random number: every chance in it is "out of 16", a roll of 0-15
 * (docs/mechanics.md). Odds in [org.tadams.nostromo.core.scenario.Rules] are in these units.
 */
const val ODDS = 16

/** The only source of randomness in the simulation. Tests can substitute a scripted one. */
interface Dice {
    /** A number from 0 until [sides]. */
    fun roll(sides: Int): Int
}

/** kotlin.random.Random(seed) gives the same sequence on every platform. */
class SeededDice(seed: Long) : Dice {
    private val random = Random(seed)
    override fun roll(sides: Int): Int = random.nextInt(sides)
}
