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
import org.tadams.nostromo.core.event.GameEvent.ItemDropped
import org.tadams.nostromo.core.event.GameEvent.ItemSwapped
import org.tadams.nostromo.core.event.GameEvent.ItemTaken
import org.tadams.nostromo.core.event.GameEvent.RoomDamaged
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.Scenario

/** The one place game text comes from, so every platform and every test sees the same wording. */
class EventText(scenario: Scenario) {
    private val actorNames = scenario.actors.associate { it.id to it.name }
    private val roomNames = scenario.rooms.associate { it.id to it.name }
    private val itemNames = scenario.itemTypes.associate { it.id to it.name }.let { types ->
        scenario.items.associate { it.id to (types[it.type] ?: it.type) }
    }

    /** Log line for [event]. */
    fun describe(event: GameEvent): String = when (event) {
        is ActionStarted -> "${name(event.actor)} starts ${doing(event.action)}"
        is ActionCancelled -> "${name(event.actor)} stops ${doing(event.action)}"
        is ActionFailed -> "${name(event.actor)} can't ${doing(event.action)}: ${reason(event.reason)}"
        is ActorMoved -> "${name(event.actor)} enters ${room(event.to)}"
        is AttackHit -> "${name(event.attacker)} hits ${name(event.target)}" + if (event.damage == 0) ", to no effect" else ""
        is ItemTaken -> "${name(event.actor)} picks up the ${item(event.item)}"
        is ItemDropped -> "${name(event.actor)} drops the ${item(event.item)}"
        is ItemSwapped -> "${name(event.actor)} swaps to the ${item(event.item)}"
        is RoomDamaged -> "Something is tearing at ${room(event.room)} (${event.damagePercent}% damaged)"
        is ActorDown -> "${name(event.actor)} is down"
        is GameOver -> when (event.result.ending) {
            Ending.CREATURE_KILLED -> "The creature is dead"
            Ending.CREW_GONE -> "The whole crew is down"
        }
        is DiceRolled -> "Roll for ${event.reason}: ${event.result} (0-${event.sides - 1})"
    }

    /** Narrator line when a fight between [attacker] and [target] starts. */
    fun fight(attacker: ActorId, target: ActorId) = "${name(attacker)} is attacking ${name(target)}"

    /** Narrator line when [target] of an ongoing fight is badly hurt. */
    fun wounded(target: ActorId) = "${name(target)} is being wounded"

    /** Label for the action bar. */
    fun option(action: Action): String = when (action) {
        is Action.Move -> "Move to ${room(action.to)}"
        is Action.Attack -> "Attack ${name(action.target)}"
        Action.Prowl -> "Prowl"
        is Action.Get -> "Get ${item(action.item)}"
        is Action.Drop -> "Drop ${item(action.item)}"
        is Action.Swap -> "Swap to ${item(action.item)}"
    }

    private fun doing(action: Action): String = when (action) {
        is Action.Move -> "moving to ${room(action.to)}"
        is Action.Attack -> "attacking ${name(action.target)}"
        Action.Prowl -> "prowling"
        is Action.Get -> "picking up the ${item(action.item)}"
        is Action.Drop -> "dropping the ${item(action.item)}"
        is Action.Swap -> "swapping to the ${item(action.item)}"
    }

    /** Name of [item]'s type, e.g. "Laser Pistol". */
    fun item(item: ItemId) = itemNames[item] ?: item.value


    private fun reason(reason: FailReason): String = when (reason) {
        FailReason.NOT_CAPABLE -> "not something they can do"
        FailReason.NOT_ADJACENT -> "no way through from here"
        FailReason.TARGET_NOT_HERE -> "they're not here"
        FailReason.TARGET_DOWN -> "they're already down"
        FailReason.ITEM_NOT_HERE -> "it isn't here"
        FailReason.HANDS_FULL -> "their hands are full"
        FailReason.NOT_HOLDING -> "they don't have it"
        FailReason.TOO_SCARED -> "too scared"
    }

    private fun name(id: ActorId) = actorNames[id] ?: id.value
    private fun room(id: RoomId) = roomNames[id] ?: id.value

    companion object {
        /** The original's morale labels (docs/mechanics.md §5). */
        fun morale(level: Int) = when {
            level <= 0 -> "Broken"
            level == 1 -> "Shaken"
            level == 2 -> "Uneasy"
            level == 3 -> "Stable"
            else -> "Confident"
        }
    }
}
