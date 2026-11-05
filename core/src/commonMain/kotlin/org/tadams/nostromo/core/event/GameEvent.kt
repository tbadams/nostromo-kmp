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

    /** [actor]'s [action] couldn't land yet, for [reason]; it will try again shortly. */
    data class ActionDelayed(
        override val tick: Long,
        val actor: ActorId,
        val action: Action,
        val reason: FailReason,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] panicked instead of doing [ignored]: they drop everything and run. */
    data class Panicked(
        override val tick: Long,
        val actor: ActorId,
        val ignored: Action,
        override val visibility: Visibility,
    ) : GameEvent

    /** [finder] found [body], a crew member who went down. Every crew member's morale drops. */
    data class BodyFound(
        override val tick: Long,
        val finder: ActorId,
        val body: ActorId,
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

    /** [actor] tried to use [item], but it has no charges left. */
    data class ItemEmpty(
        override val tick: Long,
        val actor: ActorId,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [item] is gone for good: used up, or destroyed with everything in a room. */
    data class ItemDestroyed(
        override val tick: Long,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [attacker] netted [target] with [item]: it does nothing for a while. */
    data class Frozen(
        override val tick: Long,
        val attacker: ActorId,
        val target: ActorId,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [creature] was driven off by [attackers]; an [ActorMoved] follows with where it went. */
    data class CreatureFled(
        override val tick: Long,
        val creature: ActorId,
        val attackers: List<ActorId>,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] caught [animal] in [item]. */
    data class AnimalCaught(
        override val tick: Long,
        val actor: ActorId,
        val animal: ActorId,
        val item: ItemId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] tried to catch [animal] and missed; it bolts. */
    data class AnimalEscaped(
        override val tick: Long,
        val actor: ActorId,
        val animal: ActorId,
        override val visibility: Visibility,
    ) : GameEvent

    /** [actor] is the android and has turned on the crew. Hidden: the crew learn it by seeing what it does. */
    data class AndroidRevealed(
        override val tick: Long,
        val actor: ActorId,
    ) : GameEvent {
        override val visibility get() = Visibility.Hidden
    }

    /** At the start, the creature hatched from [host], who is out of play. */
    data class Hatched(
        override val tick: Long,
        val host: ActorId,
    ) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /**
     * A sound heard ship-wide (docs/mechanics.md §9): [kind] at [room], made by [actor]. Who made it
     * is for debug; the crew hear only that it happened.
     */
    data class Noise(
        override val tick: Long,
        val kind: NoiseKind,
        val room: RoomId,
        val actor: ActorId,
    ) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /**
     * [holder]'s detector ([item]: a tracker, or a caught animal) changed reading: [movement] means
     * something alive is in a neighbouring location. Reported only when it changes.
     */
    data class DetectorReading(
        override val tick: Long,
        val holder: ActorId,
        val item: ItemId,
        val movement: Boolean,
        override val visibility: Visibility,
    ) : GameEvent

    // ---- The ship. Alarms and announcements are heard ship-wide.

    /** [actor] armed the self-destruct; it goes off in [ticksLeft]. */
    data class DestructArmed(override val tick: Long, val actor: ActorId, val ticksLeft: Long) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] overrode the self-destruct. */
    data class DestructDisarmed(override val tick: Long, val actor: ActorId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] blew [airlock]. */
    data class AirlockBlown(override val tick: Long, val actor: ActorId, val airlock: RoomId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] re-sealed [airlock]. */
    data class AirlockSealed(override val tick: Long, val actor: ActorId, val airlock: RoomId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] was lost when [airlock] was blown. Hidden: the crew know only that the airlock was blown. */
    data class BlownOut(override val tick: Long, val actor: ActorId, val airlock: RoomId) : GameEvent {
        override val visibility get() = Visibility.Hidden
    }

    /** [actor] survived being blown out and came back in at [room] ("Intruder entering"). */
    data class IntruderEntering(override val tick: Long, val actor: ActorId, val room: RoomId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] went into hypersleep. */
    data class WentToSleep(override val tick: Long, val actor: ActorId, override val visibility: Visibility) : GameEvent

    /** [actor] tried to launch, but [missing] aren't aboard. */
    data class LaunchCountermanded(override val tick: Long, val actor: ActorId, val missing: List<ActorId>) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] launched the shuttle. */
    data class Launched(override val tick: Long, val actor: ActorId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [room] caught fire. */
    data class FireStarted(override val tick: Long, val room: RoomId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** [actor] used the extinguisher on [room]'s fire; it's still burning. */
    data class FireFought(override val tick: Long, val actor: ActorId, val room: RoomId, override val visibility: Visibility) : GameEvent

    /** The fire in [room] is out. */
    data class FireOut(override val tick: Long, val room: RoomId) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** A damage warning for [room]: [kind] says which. */
    data class DamageWarning(override val tick: Long, val room: RoomId, val kind: WarningKind) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

    /** Oxygen is running low. */
    data class OxygenCritical(override val tick: Long) : GameEvent {
        override val visibility get() = Visibility.Ship
    }

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

/** Kinds of ship-wide sound. */
enum class NoiseKind {
    /** Someone or something entered a room (not a duct). */
    DOOR,

    /** A grille was removed. */
    GRILLE,
}

/** Damage warnings (docs/mechanics.md §3). */
enum class WarningKind {
    /** A key system failed at [org.tadams.nostromo.core.scenario.Rules.fireAtPercent]. */
    SYSTEM_FAILURE,

    /** Structural damage at [org.tadams.nostromo.core.scenario.Rules.structuralAtPercent]. */
    STRUCTURAL,
}

/** Why an action couldn't start. */
enum class FailReason {
    /** It can only be done somewhere else (at the controls, in the vault, aboard the shuttle). */
    WRONG_ROOM,

    /** Already armed, disarmed, blown or sealed. */
    ALREADY_DONE,

    /** There's no fire here. */
    NO_FIRE,

    /** Fighting a fire needs an extinguisher in use. */
    NEEDS_EXTINGUISHER,

    /** The item in use has no charges left. */
    ITEM_EMPTY,

    /** Not something that can be attacked (the cat is caught, not hit). */
    NOT_A_TARGET,

    /** Catching needs an empty catching item (cat box, net) in use. */
    NEEDS_CATCHER,

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

    /** Crew can't attack each other ([org.tadams.nostromo.core.scenario.Rules.friendlyFire] is off). */
    FRIENDLY_FIRE,

    /** The destination already holds as many crew as it can (bodies included). */
    ROOM_FULL,

    /** Morale is too low to obey: crew below the threshold won't attack or go into ducts. */
    TOO_SCARED,
}
