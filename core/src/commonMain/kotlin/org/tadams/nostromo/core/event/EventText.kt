package org.tadams.nostromo.core.event

import org.tadams.nostromo.core.Ending
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.event.GameEvent.ActionCancelled
import org.tadams.nostromo.core.event.GameEvent.ActionDelayed
import org.tadams.nostromo.core.event.GameEvent.ActionFailed
import org.tadams.nostromo.core.event.GameEvent.ActionStarted
import org.tadams.nostromo.core.event.GameEvent.ActorDown
import org.tadams.nostromo.core.event.GameEvent.ActorMoved
import org.tadams.nostromo.core.event.GameEvent.AttackHit
import org.tadams.nostromo.core.event.GameEvent.BodyFound
import org.tadams.nostromo.core.event.GameEvent.DiceRolled
import org.tadams.nostromo.core.event.GameEvent.GameOver
import org.tadams.nostromo.core.event.GameEvent.GrilleRemoved
import org.tadams.nostromo.core.event.GameEvent.ItemDropped
import org.tadams.nostromo.core.event.GameEvent.ItemSwapped
import org.tadams.nostromo.core.event.GameEvent.ItemTaken
import org.tadams.nostromo.core.event.GameEvent.Panicked
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
        is ActionDelayed -> "${name(event.actor)} waits before ${doing(event.action)}: ${reason(event.reason)}"
        is Panicked -> "${name(event.actor)} panics!"
        is BodyFound -> "${name(event.finder)} finds ${name(event.body)}'s body"
        is ActorMoved -> "${name(event.actor)} enters ${room(event.to)}"
        is AttackHit -> "${name(event.attacker)} hits ${name(event.target)}" + if (event.damage == 0) ", to no effect" else ""
        is GrilleRemoved -> "${name(event.actor)} removes the grille in ${room(event.room)}"
        is ItemTaken -> "${name(event.actor)} picks up the ${item(event.item)}"
        is ItemDropped -> "${name(event.actor)} drops the ${item(event.item)}"
        is ItemSwapped -> "${name(event.actor)} swaps to the ${item(event.item)}"
        is RoomDamaged -> "Something is tearing at ${room(event.room)} (${event.damagePercent}% damaged)"
        is ActorDown -> "${name(event.actor)} is down"
        is GameEvent.ItemEmpty -> "${name(event.actor)}'s ${item(event.item)} is empty"
        is GameEvent.ItemDestroyed -> "The ${item(event.item)} is destroyed"
        is GameEvent.Frozen -> "${name(event.attacker)} nets ${name(event.target)}"
        is GameEvent.CreatureFled -> "${name(event.creature)} is driven off"
        is GameEvent.AnimalCaught -> "${name(event.actor)} catches ${name(event.animal)}"
        is GameEvent.AnimalEscaped -> "${name(event.animal)} slips away from ${name(event.actor)}"
        is GameEvent.AndroidRevealed -> "${name(event.actor)} is the android, and turns on the crew"
        is GameEvent.Hatched -> "The creature has hatched from ${name(event.host)}"
        is GameEvent.Noise -> noise(event)
        is GameEvent.DetectorReading ->
            if (event.movement) "${name(event.holder)}'s ${item(event.item)}: movement nearby" else "${name(event.holder)}'s ${item(event.item)}: all clear"
        is GameEvent.DestructArmed -> "SELF-DESTRUCT ARMED by ${name(event.actor)}"
        is GameEvent.DestructDisarmed -> "Self-destruct overridden by ${name(event.actor)}"
        is GameEvent.AirlockBlown -> "WARNING: ${room(event.airlock)} depressurised"
        is GameEvent.AirlockSealed -> "${room(event.airlock)}: pressure restored"
        is GameEvent.BlownOut -> "${name(event.actor)} is blown out of ${room(event.airlock)}"
        is GameEvent.IntruderEntering -> "INTRUDER ENTERING ${room(event.room)}"
        is GameEvent.WentToSleep -> "${name(event.actor)} goes into hypersleep"
        is GameEvent.LaunchCountermanded -> "LAUNCH COUNTERMANDED: ${event.missing.joinToString { name(it) }} not aboard"
        is GameEvent.Launched -> "The shuttle launches"
        is GameEvent.FireStarted -> "WARNING: fire in ${room(event.room)}"
        is GameEvent.FireFought -> "${name(event.actor)} fights the fire in ${room(event.room)}"
        is GameEvent.FireOut -> "Fire extinguished in ${room(event.room)}"
        is GameEvent.DamageWarning -> when (event.kind) {
            WarningKind.SYSTEM_FAILURE -> "WARNING: systems failing in ${room(event.room)}"
            WarningKind.STRUCTURAL -> "WARNING: structural damage in ${room(event.room)}"
        }
        is GameEvent.OxygenCritical -> "WARNING: oxygen critical"
        is GameOver -> ending(event.result.ending, event.result.destructArmed)
        is DiceRolled -> "Roll for ${event.reason}: ${event.result} (0-${event.sides - 1})"
    }

    /** One line for how the game ended. */
    fun ending(ending: Ending, destructArmed: Boolean) = when (ending) {
        Ending.CREATURE_KILLED -> "The creature has been killed"
        Ending.CREW_GONE -> "Humanoid life levels minimal"
        Ending.LAUNCHED -> if (destructArmed) "The shuttle is launched. The ship will explode behind you" else
            "The shuttle is launched. The creature will be unleashed upon Earth"
        Ending.OXYGEN_OUT -> "Life support systems exhausted"
        Ending.SHIP_DESTROYED -> "The ship has exploded"
    }

    /** Raw line for a sound, naming where and who (debug detail). */
    private fun noise(event: GameEvent.Noise) = when (event.kind) {
        NoiseKind.DOOR -> "Door sound: ${name(event.actor)} enters ${room(event.room)}"
        NoiseKind.GRILLE -> "Grille sound: ${name(event.actor)} removes the grille in ${room(event.room)}"
    }

    /** Narrator line for a sound the crew hear but can't place. */
    fun heard(kind: NoiseKind) = when (kind) {
        NoiseKind.DOOR -> "A door opens somewhere"
        NoiseKind.GRILLE -> "A grille clangs somewhere"
    }

    /** Narrator line when a fight between [attacker] and [target] starts. */
    fun fight(attacker: ActorId, target: ActorId) = "${name(attacker)} is attacking ${name(target)}"

    /** Narrator line when [target] of an ongoing fight is badly hurt. */
    fun wounded(target: ActorId) = "${name(target)} is being wounded"

    /**
     * Label for the action bar.
     * TODO consider restoring the original's duct labels (docs/mechanics.md §3,
     *  reference/notes/20_commands_crew.md §2): "Grille" for the way between a room and its duct,
     *  rather than the name of where it leads, and North/East/South/West for moves along ducts,
     *  rather than the next duct segment's name. Needs each duct link's direction from the original's
     *  N/E/S/W duct table, which the scenario doesn't carry yet (tools/make_scenario.py drops it).
     */
    fun option(action: Action): String = when (action) {
        is Action.Move -> "Move to ${room(action.to)}"
        is Action.Attack -> "Attack ${name(action.target)}"
        Action.Prowl -> "Prowl"
        Action.RemoveGrille -> "Remove grille"
        is Action.Get -> "Get ${item(action.item)}"
        is Action.Drop -> "Drop ${item(action.item)}"
        is Action.Swap -> "Swap to ${item(action.item)}"
        is Action.Catch -> "Catch ${name(action.target)}"
        Action.ArmDestruct -> "Arm self-destruct"
        Action.DisarmDestruct -> "Override self-destruct"
        is Action.BlowAirlock -> "Blow ${room(action.airlock)}"
        is Action.SealAirlock -> "Seal ${room(action.airlock)}"
        Action.Hypersleep -> "Hypersleep"
        Action.Launch -> "Launch shuttle"
        Action.FightFire -> "Fight fire"
    }

    private fun doing(action: Action): String = when (action) {
        is Action.Move -> "moving to ${room(action.to)}"
        is Action.Attack -> "attacking ${name(action.target)}"
        Action.Prowl -> "prowling"
        Action.RemoveGrille -> "removing the grille"
        is Action.Get -> "picking up the ${item(action.item)}"
        is Action.Drop -> "dropping the ${item(action.item)}"
        is Action.Swap -> "swapping to the ${item(action.item)}"
        is Action.Catch -> "catching ${name(action.target)}"
        else -> option(action).lowercase()
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
        FailReason.ROOM_FULL -> "there's no room"
        FailReason.FRIENDLY_FIRE -> "they won't attack crew"
        FailReason.GRILLE_IN_PLACE -> "the grille is in place"
        FailReason.NO_GRILLE -> "there's no grille here"
        FailReason.GRILLE_ALREADY_REMOVED -> "the grille is already off"
        FailReason.NOT_A_TARGET -> "that can't be attacked"
        FailReason.NEEDS_CATCHER -> "they need something to catch it in"
        FailReason.WRONG_ROOM -> "not from here"
        FailReason.ALREADY_DONE -> "it's already done"
        FailReason.NO_FIRE -> "there's no fire here"
        FailReason.NEEDS_EXTINGUISHER -> "they need an extinguisher"
        FailReason.ITEM_EMPTY -> "it's empty"
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
