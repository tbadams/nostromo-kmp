package org.tadams.nostromo.core.event

import org.tadams.nostromo.core.GameResult
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId

/** Who can know about an event. The log shows [Hidden] events only in debug mode. */
sealed interface Visibility {
    /** Seen or heard by these crew. Empty means nobody noticed. */
    data class Perceived(val by: Set<ActorId>) : Visibility

    /** Ship-wide alarm or announcement. */
    data object Ship : Visibility

    /** Internal detail: AI decisions, dice rolls, timers, hidden roles. */
    data object Hidden : Visibility
}

/** Something that happened in the simulation, for the log and the UI. */
sealed interface GameEvent {
    /** The tick it happened on. */
    val tick: Long

    /** Who could know about it. */
    val visibility: Visibility

    /** [actor] began [action], which will take [totalTicks]. */
    data class ActionStarted(
        override val tick: Long,
        val actor: ActorId,
        val action: Action,
        val totalTicks: Int,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor]'s [action] was stopped before it finished, with no effect. */
    data class ActionCancelled(
        override val tick: Long,
        val actor: ActorId,
        val action: Action,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] couldn't start [action]; their remaining queue was dropped. */
    data class ActionFailed(
        override val tick: Long,
        val actor: ActorId,
        val action: Action,
        val reason: FailReason,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] arrived in [to] from [from]. */
    data class ActorMoved(
        override val tick: Long,
        val actor: ActorId,
        val from: RoomId,
        val to: RoomId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [attacker] hit [target] for [damage] (0 if their weapon does nothing), leaving [healthLeft]. */
    data class AttackHit(
        override val tick: Long,
        val attacker: ActorId,
        val target: ActorId,
        val damage: Int,
        val healthLeft: Int,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] picked up [item]. */
    data class ItemTaken(
        override val tick: Long,
        val actor: ActorId,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] put down [item]. */
    data class ItemDropped(
        override val tick: Long,
        val actor: ActorId,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] put their carried [item] into use. */
    data class ItemSwapped(
        override val tick: Long,
        val actor: ActorId,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] removed [room]'s grille, opening the way between it and its duct. */
    data class GrilleRemoved(
        override val tick: Long,
        val actor: ActorId,
        val room: RoomId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [room] took damage, and is now at [damagePercent]. */
    data class RoomDamaged(
        override val tick: Long,
        val room: RoomId,
        val damagePercent: Int,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] went down: crew collapse, the creature dies. */
    data class ActorDown(
        override val tick: Long,
        val actor: ActorId,
        override val visibility: Visibility,
    ) : GameEvent

    /** The game is over. Everyone hears about it. */
    data class GameOver(
        override val tick: Long,
        val result: GameResult,
    ) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** A random roll of 0 until [sides] for [reason]. Always [Visibility.Hidden]. */
    data class DiceRolled(
        override val tick: Long,
        val reason: String,
        val sides: Int,
        val result: Int,
    ) : GameEvent {
        override val visibility get() = Visibility.Hidden
    }
}

/** Why an action couldn't start. */
enum class FailReason {
    /** This actor can never take this type of action (e.g. the cat attacking). */
    NOT_CAPABLE,

    /** The destination isn't connected to the actor's current location. */
    NOT_ADJACENT,

    /** The target isn't in the same location. */
    TARGET_NOT_HERE,

    /** The target is already down. */
    TARGET_DOWN,

    /** The item isn't lying here. */
    ITEM_NOT_HERE,

    /** Both the in-use and carried slots are taken. */
    HANDS_FULL,

    /** The actor doesn't hold that item (in the slot the action needs). */
    NOT_HOLDING,

    /** A grille is in place on the way through. */
    GRILLE_IN_PLACE,

    /** There's no grille here to remove. */
    NO_GRILLE,

    /** The grille here is already off. */
    GRILLE_ALREADY_REMOVED,

    /** Morale is too low to obey: crew below the threshold won't attack or go into ducts. */
    TOO_SCARED,
}
