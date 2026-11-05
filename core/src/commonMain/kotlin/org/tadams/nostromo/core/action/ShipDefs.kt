package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.rng.ODDS
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.RoomFeature
import org.tadams.nostromo.core.scenario.Rules

/*
 * The ship's special actions (docs/mechanics.md §7, §10), each offered only in its room. All were
 * instant menu choices in the original, so they take one tick.
 */

private fun Actor.isIn(world: World, feature: RoomFeature) = feature in world.room(room).features

/** Starting the self-destruct. */
internal object ArmDestructDef : ActionDef<Action.ArmDestruct> {
    override val type = ActionType.ARM_DESTRUCT

    override fun candidates(actor: Actor, world: World) =
        if (actor.isIn(world, RoomFeature.DESTRUCT_CONTROLS) && !world.ship.destructArmed) listOf(Action.ArmDestruct) else emptyList()

    override fun check(actor: Actor, world: World, action: Action.ArmDestruct, rules: Rules): FailReason? = when {
        !actor.isIn(world, RoomFeature.DESTRUCT_CONTROLS) -> FailReason.WRONG_ROOM
        world.ship.destructArmed -> FailReason.ALREADY_DONE
        else -> null
    }

    override fun complete(actor: Actor, world: World, action: Action.ArmDestruct, scope: TickScope) {
        val ticks = Time.ticks(scope.rules.destructSeconds).toLong()
        world.ship.destructTicksLeft = ticks
        scope.emit(GameEvent.DestructArmed(scope.tick, actor.id, ticks))
    }
}

/** Stopping the self-destruct. Arming again starts from the top. */
internal object DisarmDestructDef : ActionDef<Action.DisarmDestruct> {
    override val type = ActionType.DISARM_DESTRUCT

    override fun candidates(actor: Actor, world: World) =
        if (actor.isIn(world, RoomFeature.DESTRUCT_CONTROLS) && world.ship.destructArmed) listOf(Action.DisarmDestruct) else emptyList()

    override fun check(actor: Actor, world: World, action: Action.DisarmDestruct, rules: Rules): FailReason? = when {
        !actor.isIn(world, RoomFeature.DESTRUCT_CONTROLS) -> FailReason.WRONG_ROOM
        !world.ship.destructArmed -> FailReason.ALREADY_DONE
        else -> null
    }

    override fun complete(actor: Actor, world: World, action: Action.DisarmDestruct, scope: TickScope) {
        world.ship.destructTicksLeft = null
        scope.emit(GameEvent.DestructDisarmed(scope.tick, actor.id))
    }
}

/**
 * Blowing an airlock from the controls (docs/mechanics.md §7): everyone inside is lost, the creature
 * included, and every item there is destroyed. A creature with [Rules.airlockKillDamage] or more
 * damage dies; otherwise it survives [Rules.airlockSurvivalOdds] in 16 and comes back in at the
 * intruder entry. While blown, a move into the airlock lands at the controls instead.
 */
internal object BlowAirlockDef : ActionDef<Action.BlowAirlock> {
    override val type = ActionType.BLOW_AIRLOCK

    override fun candidates(actor: Actor, world: World) =
        if (!actor.isIn(world, RoomFeature.AIRLOCK_CONTROLS)) emptyList()
        else world.rooms.values.filter { RoomFeature.AIRLOCK in it.features && !it.blown }.map { Action.BlowAirlock(it.id) }

    override fun check(actor: Actor, world: World, action: Action.BlowAirlock, rules: Rules): FailReason? {
        val airlock = world.rooms[action.airlock]
        return when {
            !actor.isIn(world, RoomFeature.AIRLOCK_CONTROLS) -> FailReason.WRONG_ROOM
            airlock == null || RoomFeature.AIRLOCK !in airlock.features -> FailReason.WRONG_ROOM
            airlock.blown -> FailReason.ALREADY_DONE
            else -> null
        }
    }

    override fun complete(actor: Actor, world: World, action: Action.BlowAirlock, scope: TickScope) {
        val airlock = world.room(action.airlock)
        airlock.blown = true
        scope.emit(GameEvent.AirlockBlown(scope.tick, actor.id, airlock.id))
        val seenBy = Visibility.Perceived(world.witnesses(listOf(airlock.id)))
        for (item in world.items.values.filter { !it.destroyed && (it.room == airlock.id || world.holderOf(it)?.room == airlock.id) }) {
            world.destroy(item.id)?.let { it.gone = true }
            scope.emit(GameEvent.ItemDestroyed(scope.tick, item.id, seenBy))
        }
        for (victim in world.occupants(airlock.id)) {
            victim.current = null
            victim.queue.clear()
            if (victim.kind == ActorKind.CREATURE && !victim.isDown) {
                val damage = victim.maxHealth - victim.health
                val survives = damage < scope.rules.airlockKillDamage &&
                    scope.roll(ODDS, "${victim.name} survives the airlock") < scope.rules.airlockSurvivalOdds
                val entry = world.roomWith(RoomFeature.INTRUDER_ENTRY)
                if (survives && entry != null) {
                    victim.room = entry.id
                    scope.emit(GameEvent.IntruderEntering(scope.tick, victim.id, entry.id))
                    continue
                }
                victim.health = 0
            }
            victim.gone = true
            scope.emit(GameEvent.BlownOut(scope.tick, victim.id, airlock.id))
        }
    }
}

/** Re-sealing a blown airlock. */
internal object SealAirlockDef : ActionDef<Action.SealAirlock> {
    override val type = ActionType.SEAL_AIRLOCK

    override fun candidates(actor: Actor, world: World) =
        if (!actor.isIn(world, RoomFeature.AIRLOCK_CONTROLS)) emptyList()
        else world.rooms.values.filter { RoomFeature.AIRLOCK in it.features && it.blown }.map { Action.SealAirlock(it.id) }

    override fun check(actor: Actor, world: World, action: Action.SealAirlock, rules: Rules): FailReason? {
        val airlock = world.rooms[action.airlock]
        return when {
            !actor.isIn(world, RoomFeature.AIRLOCK_CONTROLS) -> FailReason.WRONG_ROOM
            airlock == null || !airlock.blown -> FailReason.ALREADY_DONE
            else -> null
        }
    }

    override fun complete(actor: Actor, world: World, action: Action.SealAirlock, scope: TickScope) {
        world.room(action.airlock).blown = false
        scope.emit(GameEvent.AirlockSealed(scope.tick, actor.id, action.airlock))
    }
}

/** Going into hypersleep: out of play, safe; items stay in the vault. Counts as not alive for the endings. */
internal object HypersleepDef : ActionDef<Action.Hypersleep> {
    override val type = ActionType.HYPERSLEEP

    override fun candidates(actor: Actor, world: World) =
        if (actor.isIn(world, RoomFeature.HYPERSLEEP)) listOf(Action.Hypersleep) else emptyList()

    override fun check(actor: Actor, world: World, action: Action.Hypersleep, rules: Rules): FailReason? =
        if (!actor.isIn(world, RoomFeature.HYPERSLEEP)) FailReason.WRONG_ROOM else null

    override fun complete(actor: Actor, world: World, action: Action.Hypersleep, scope: TickScope) {
        val seenBy = Visibility.Perceived(world.witnesses(listOf(actor.room)))
        listOfNotNull(actor.inUse, actor.carried).forEach { world.drop(actor, it) }
        world.carryCaught()
        actor.asleep = true
        scope.emit(GameEvent.WentToSleep(scope.tick, actor.id, seenBy))
    }
}

/**
 * Launching the shuttle (docs/mechanics.md §10): every crew member still in play, an unrevealed
 * android included, must be aboard, and so must every animal, loose or caught. The creature isn't
 * checked. Otherwise "launch countermanded".
 */
internal object LaunchDef : ActionDef<Action.Launch> {
    override val type = ActionType.LAUNCH

    override fun candidates(actor: Actor, world: World) =
        if (actor.isIn(world, RoomFeature.LAUNCH)) listOf(Action.Launch) else emptyList()

    override fun check(actor: Actor, world: World, action: Action.Launch, rules: Rules): FailReason? =
        if (!actor.isIn(world, RoomFeature.LAUNCH)) FailReason.WRONG_ROOM else null

    override fun complete(actor: Actor, world: World, action: Action.Launch, scope: TickScope) {
        val missing = world.actors.values.filter {
            val needed = (it.isCrew && !it.revealed) || it.kind == ActorKind.ANIMAL
            needed && !it.outOfPlay && it.room != actor.room
        }
        if (missing.isNotEmpty()) {
            scope.emit(GameEvent.LaunchCountermanded(scope.tick, actor.id, missing.map { it.id }))
            return
        }
        world.ship.launched = true
        scope.emit(GameEvent.Launched(scope.tick, actor.id))
    }
}

/**
 * Fighting the fire here with the extinguisher in use: one charge per go. It goes out after enough
 * goes for the room's damage ([Rules.fireFightsFor]).
 */
internal object FightFireDef : ActionDef<Action.FightFire> {
    override val type = ActionType.FIGHT_FIRE

    override fun candidates(actor: Actor, world: World) =
        if (world.room(actor.room).burning) listOf(Action.FightFire) else emptyList()

    override fun check(actor: Actor, world: World, action: Action.FightFire, rules: Rules): FailReason? {
        val item = world.inUse(actor)
        return when {
            !world.room(actor.room).burning -> FailReason.NO_FIRE
            item == null || !item.type.fightsFire -> FailReason.NEEDS_EXTINGUISHER
            item.isEmpty -> FailReason.ITEM_EMPTY
            else -> null
        }
    }

    override fun complete(actor: Actor, world: World, action: Action.FightFire, scope: TickScope) {
        val room = world.room(actor.room)
        val item = world.inUse(actor) ?: return
        item.charges?.let { item.charges = it - 1 }
        room.fireFights++
        if (room.fireFights >= scope.rules.fireFightsFor(room.damagePercent)) {
            room.burning = false
            room.fireFights = 0
            scope.emit(GameEvent.FireOut(scope.tick, room.id))
        } else {
            scope.emit(GameEvent.FireFought(scope.tick, actor.id, room.id, Visibility.Perceived(world.witnesses(listOf(room.id)))))
        }
    }
}
