package org.tadams.nostromo.core.rng

import kotlin.random.Random

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
