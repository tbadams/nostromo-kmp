package org.tadams.nostromo.core.action

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.RoomId

/** Something an actor does over time. It only changes the world when it completes. */
sealed interface Action {
    /** Which kind of action this is; durations are looked up by type. */
    val type: ActionType

    /** Walk, climb or crawl to an adjacent location [to]. */
    data class Move(val to: RoomId) : Action {
        override val type get() = ActionType.MOVE
    }

    /** Strike [target], who must be in the same location when the attack starts and when it lands. */
    data class Attack(val target: ActorId) : Action {
        override val type get() = ActionType.ATTACK
    }
}

/**
 * The kinds of action, named as they appear in scenario JSON. [defaultSeconds] is the base duration
 * when a scenario doesn't set one: the original game's value converted to seconds (ticks ÷ 13.5).
 */
@Serializable
enum class ActionType(val defaultSeconds: Double) {
    /** Original: 64 ticks. */
    @SerialName("move") MOVE(4.7),

    /** Original: 40 ticks for crew. */
    @SerialName("attack") ATTACK(3.0),
}

/** Player input. Applied at the start of the next tick. */
sealed interface Command {
    /** Who the command is for. */
    val actor: ActorId

    /** Replace whatever [actor] is doing with [actions], run in order. */
    data class Order(override val actor: ActorId, val actions: List<Action>) : Command {
        constructor(actor: ActorId, action: Action) : this(actor, listOf(action))

        init {
            require(actions.isNotEmpty()) { "An order needs at least one action" }
        }
    }

    /** Stop the current action and drop any queued ones. */
    data class Cancel(override val actor: ActorId) : Command
}

/** Whether [org.tadams.nostromo.core.Simulation.submit] queued a command. */
sealed interface CommandResult {
    /** Queued; applied at the start of the next tick. */
    data object Accepted : CommandResult

    /** Not queued, for [reason]. */
    data class Rejected(val reason: String) : CommandResult
}

/** One entry in the flat action bar. */
data class ActionOption(val label: String, val action: Action)
