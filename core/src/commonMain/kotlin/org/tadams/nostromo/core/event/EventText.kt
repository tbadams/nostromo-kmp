package org.tadams.nostromo.core.event

import org.tadams.nostromo.core.Ending
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.event.GameEvent.ActionCancelled
import org.tadams.nostromo.core.event.GameEvent.ActionFailed
import org.tadams.nostromo.core.event.GameEvent.ActionStarted
import org.tadams.nostromo.core.event.GameEvent.ActorDown
import org.tadams.nostromo.core.event.GameEvent.ActorMoved
import org.tadams.nostromo.core.event.GameEvent.AttackHit
import org.tadams.nostromo.core.event.GameEvent.DiceRolled
import org.tadams.nostromo.core.event.GameEvent.GameOver
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.Scenario

/** The one place game text comes from, so every platform and every test sees the same wording. */
class EventText(scenario: Scenario) {
    private val actorNames = scenario.actors.associate { it.id to it.name }
    private val roomNames = scenario.rooms.associate { it.id to it.name }

    /** Log line for [event]. */
    fun describe(event: GameEvent): String = when (event) {
        is ActionStarted -> "${name(event.actor)} starts ${doing(event.action)}"
        is ActionCancelled -> "${name(event.actor)} stops ${doing(event.action)}"
        is ActionFailed -> "${name(event.actor)} can't ${doing(event.action)}: ${reason(event.reason)}"
        is ActorMoved -> "${name(event.actor)} enters ${room(event.to)}"
        is AttackHit -> "${name(event.attacker)} hits ${name(event.target)}"
        is ActorDown -> "${name(event.actor)} is down"
        is GameOver -> when (event.result.ending) {
            Ending.CREATURE_KILLED -> "The creature is dead"
            Ending.CREW_GONE -> "The whole crew is down"
        }
        is DiceRolled -> "Roll for ${event.reason}: ${event.result} (0-${event.sides - 1})"
    }

    /** Label for the action bar. */
    fun option(action: Action): String = when (action) {
        is Action.Move -> "Move to ${room(action.to)}"
        is Action.Attack -> "Attack ${name(action.target)}"
    }

    private fun doing(action: Action): String = when (action) {
        is Action.Move -> "moving to ${room(action.to)}"
        is Action.Attack -> "attacking ${name(action.target)}"
    }

    private fun reason(reason: FailReason): String = when (reason) {
        FailReason.NOT_ADJACENT -> "no way through from here"
        FailReason.TARGET_NOT_HERE -> "they're not here"
        FailReason.TARGET_DOWN -> "they're already down"
    }

    private fun name(id: ActorId) = actorNames[id] ?: id.value
    private fun room(id: RoomId) = roomNames[id] ?: id.value
}
