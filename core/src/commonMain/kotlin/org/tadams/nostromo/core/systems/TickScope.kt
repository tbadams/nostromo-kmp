package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.Rules

/**
 * Handle for the input ([Rules], [Dice]) and output ([events]) games state logic within a single
 * simulation tick. Valid only for that tick.
 */
class TickScope(val tick: Long, val rules: Rules, private val dice: Dice) {
    
    /** Everything emitted so far this tick, in order. */
    val events: List<GameEvent>
        field = mutableListOf<GameEvent>()

    /** Records [event] for this tick. */
    fun emit(event: GameEvent) {
        events += event
    }

    /** Roll 0 until [sides], logged as a hidden event so debug mode shows every roll and why. */
    fun roll(sides: Int, reason: String): Int {
        val result = dice.roll(sides)
        emit(GameEvent.DiceRolled(tick, reason, sides, result))
        return result
    }
}
