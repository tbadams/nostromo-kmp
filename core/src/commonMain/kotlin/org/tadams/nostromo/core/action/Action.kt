package org.tadams.nostromo.core.action

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
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

    /**
     * Remove the grille between this location and its room or duct, opening the way through. From
     * either side.
     */
    data object RemoveGrille : Action {
        override val type get() = ActionType.REMOVE_GRILLE
    }

    /**
     * Linger where you are, damaging the room (not a duct). The creature's version of the original's
     * "move to its own room" (docs/mechanics.md §6).
     */
    data object Prowl : Action {
        override val type get() = ActionType.PROWL
    }

    /** Pick up [item] from the floor: into use if nothing is, otherwise as the carried spare. */
    data class Get(val item: ItemId) : Action {
        override val type get() = ActionType.GET
    }

    /** Put down [item], in use or carried, in the current location. */
    data class Drop(val item: ItemId) : Action {
        override val type get() = ActionType.DROP
    }

    /** Put the carried [item] into use, swapping it with whatever was in use. */
    data class Swap(val item: ItemId) : Action {
        override val type get() = ActionType.SWAP
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

    /** Original: 50 ticks for the creature; 80-180 for crew, set per crew member in the scenario. */
    @SerialName("removeGrille") REMOVE_GRILLE(3.7),

    /** Original: the creature's 80-tick move, landing on its own room. */
    @SerialName("prowl") PROWL(5.9),

    /** Instant in the original, so one tick (and no speed penalty; see Rules.unslowedActions). */
    @SerialName("get") GET(0.1),

    /** Instant in the original. */
    @SerialName("drop") DROP(0.1),

    /** Instant in the original. */
    @SerialName("swap") SWAP(0.1),
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
