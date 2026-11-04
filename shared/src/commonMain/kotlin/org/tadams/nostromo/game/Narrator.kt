package org.tadams.nostromo.game

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.event.EventText
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.ActorId

/**
 * Turns the event stream into the log. `:core` reports every fact; this decides how to tell it,
 * with memory of what's already been said (docs/architecture.md, "Narrator").
 *
 * Every event comes out either as narrated lines (`hidden = false`) or as its single raw line,
 * dimmed (`hidden = true`), so debug mode loses nothing.
 *
 * Fights: one line when a fight starts, one when the victim becomes wounded, nothing per hit.
 * A fight ends when either side goes down or moves. A collapse is only told if someone other than
 * the victim saw it.
 */
class Narrator(private val text: EventText) {

    private enum class Stage { ATTACKING, WOUNDING }

    /** Fights already reported, by (attacker, target). */
    private val openFights = mutableMapOf<Pair<ActorId, ActorId>, Stage>()

    /** Log lines for [event], in order. Call once per event, in the order they happened. */
    fun narrate(event: GameEvent): List<LogEntry> {
        val lines = if (isPlayerVisible(event)) tell(event) else emptyList()
        // Whether or not anyone saw it, going down or leaving ends their fights.
        when (event) {
            is GameEvent.ActorDown -> closeFights(event.actor)
            is GameEvent.ActorMoved -> closeFights(event.actor)
            else -> {}
        }
        return lines.map { LogEntry(event.tick, it, hidden = false) }
            .ifEmpty { listOf(LogEntry(event.tick, text.describe(event), hidden = true)) }
    }

    private fun tell(event: GameEvent): List<String> = when (event) {
        is GameEvent.ActionStarted -> when (val action = event.action) {
            is Action.Attack -> open(event.actor, action.target, wounded = false)
            // Instant: the ItemTaken / ItemDropped / ItemSwapped a tick later says it all.
            is Action.Get, is Action.Drop, is Action.Swap -> emptyList()
            else -> listOf(text.describe(event))
        }
        is GameEvent.AttackHit -> hit(event)
        else -> listOf(text.describe(event))
    }

    private fun hit(event: GameEvent.AttackHit): List<String> {
        val key = event.attacker to event.target
        val wounded = event.healthLeft in 1..WOUNDED_AT
        val stage = openFights[key] ?: return open(event.attacker, event.target, wounded)
        if (stage == Stage.ATTACKING && wounded) {
            openFights[key] = Stage.WOUNDING
            return listOf(text.wounded(event.target))
        }
        return emptyList()
    }

    /** Reports a new fight, or nothing if it's already been reported. */
    private fun open(attacker: ActorId, target: ActorId, wounded: Boolean): List<String> {
        val key = attacker to target
        if (key in openFights) return emptyList()
        openFights[key] = if (wounded) Stage.WOUNDING else Stage.ATTACKING
        return listOf(text.fight(attacker, target))
    }

    private fun closeFights(actor: ActorId) {
        openFights.keys.removeAll { (attacker, target) -> attacker == actor || target == actor }
    }

    /** Whether a player could know about [event]. Someone going down doesn't witness their own collapse. */
    private fun isPlayerVisible(event: GameEvent) = when (val v = event.visibility) {
        is Visibility.Perceived -> when (event) {
            is GameEvent.ActorDown -> (v.by - event.actor).isNotEmpty()
            else -> v.by.isNotEmpty()
        }
        Visibility.Ship -> true
        Visibility.Hidden -> false
    }

    private companion object {
        /** Health at or below which a fight's victim is "being wounded": the original's 2-3, minus 1 (docs/mechanics.md §4, §6). */
        const val WOUNDED_AT = 2
    }
}
