package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.Grilles
import org.tadams.nostromo.core.model.Item
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.rng.ODDS
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.ItemEffect
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.systems.MoraleSystem

/**
 * Striking someone in the same location, with whatever is in use (docs/mechanics.md §7): plain
 * damage, charges, room damage, the net's freeze, the harpoon killing the whole room, and the
 * chance to drive the creature off.
 */
internal object AttackDef : ActionDef<Action.Attack> {
    override val type = ActionType.ATTACK

    /**
     * Anyone else here who is still up, except fellow crew unless [Rules.friendlyFire] is on (checked
     * in [check], so it applies to orders too). Whether an actor *wants* to hit them is the chooser's business.
     */
    override fun candidates(actor: Actor, world: World) =
        world.occupants(actor.room).filter { it.id != actor.id && !it.isDown && it.kind != ActorKind.ANIMAL }.map { Action.Attack(it.id) }

    override fun check(actor: Actor, world: World, action: Action.Attack, rules: Rules): FailReason? {
        val target = world.actors.getValue(action.target)
        return when {
            // Animals are to be caught, not hit (docs/mechanics.md §9).
            target.kind == ActorKind.ANIMAL -> FailReason.NOT_A_TARGET
            target.isDown -> FailReason.TARGET_DOWN
            target.room != actor.room -> FailReason.TARGET_NOT_HERE
            // Crew can't hit each other, but a revealed android is fair game, and it can hit them.
            actor.isLoyalCrew && target.isLoyalCrew && !rules.friendlyFire -> FailReason.FRIENDLY_FIRE
            else -> null
        }
    }

    override fun complete(actor: Actor, world: World, action: Action.Attack, scope: TickScope) {
        val target = world.actors.getValue(action.target)
        val here = world.room(actor.room)
        // Every hit is reported; the log narrator in :shared (game/Narrator) folds a fight into a line or two.
        // Work out who saw it before the hit lands, so a crew member who goes down still saw it.
        val seenBy = Visibility.Perceived(world.witnesses(listOf(actor.room)))
        // Only loyal crew fight with items; one with an animal caught in it is no weapon.
        val item = if (actor.isLoyalCrew) world.inUse(actor)?.takeIf { it.caught == null } else null

        if (item != null && item.isEmpty) {
            scope.emit(GameEvent.ItemEmpty(scope.tick, actor.id, item.id, seenBy))
            return
        }
        val charges = item?.charges
        if (item != null && charges != null) item.charges = charges - 1

        if (item != null && item.type.roomDamagePercent > 0 && here.kind == RoomKind.ROOM) {
            here.damage(item.type.roomDamagePercent)
            scope.emit(GameEvent.RoomDamaged(scope.tick, here.id, here.damagePercent, seenBy))
        }

        val effect = item?.type?.effect
        when {
            item != null && effect == ItemEffect.FREEZE -> {
                target.frozenUntil = scope.tick + Time.ticks(scope.rules.netFreezeSeconds)
                target.current = null
                target.queue.clear()
                scope.emit(GameEvent.Frozen(scope.tick, actor.id, target.id, item.id, seenBy))
            }
            item != null && effect == ItemEffect.KILL_ROOM -> {
                hit(actor, target, item.type.damage, world, scope, seenBy)
                killRoom(actor, world, scope, seenBy)
            }
            else -> {
                val damage = damage(actor, item, scope)
                hit(actor, target, damage, world, scope, seenBy)
                if (!target.isDown && (damage > 0 || item?.type?.scares == true)) maybeFlee(target, actor, world, scope)
            }
        }

        if (item != null && !item.destroyed && item.isEmpty && item.type.breaksWhenSpent) {
            world.destroy(item.id)
            scope.emit(GameEvent.ItemDestroyed(scope.tick, item.id, seenBy))
        }
    }

    /** Takes [damage] off [target] and knocks them down if that's the last of their health. */
    private fun hit(actor: Actor, target: Actor, damage: Int, world: World, scope: TickScope, seenBy: Visibility) {
        target.health = (target.health - damage).coerceAtLeast(0)
        scope.emit(GameEvent.AttackHit(scope.tick, actor.id, target.id, damage, target.health, seenBy))
        if (target.isDown) knockDown(target, world, scope, seenBy)
    }

    /**
     * The harpoon: every crew member here goes down, the shooter included, and every item here,
     * held or lying, is destroyed (docs/mechanics.md §7).
     */
    private fun killRoom(actor: Actor, world: World, scope: TickScope, seenBy: Visibility) {
        val room = actor.room
        for (victim in world.occupants(room).filter { it.isCrew && !it.isDown }) {
            victim.health = 0
            knockDown(victim, world, scope, seenBy)
        }
        val items = world.items.values.filter { !it.destroyed && (it.room == room || world.holderOf(it)?.room == room) }
        for (item in items) {
            world.destroy(item.id)?.let { scope.emit(GameEvent.ActorDown(scope.tick, it.id, seenBy)) }
            scope.emit(GameEvent.ItemDestroyed(scope.tick, item.id, seenBy))
        }
    }

    /** Health one hit takes off: loyal crew do what their item does (nothing bare-handed); anyone else [Rules.attackDamage]. */
    private fun damage(actor: Actor, item: Item?, scope: TickScope): Int = when {
        item != null -> item.type.damage
        actor.isLoyalCrew -> 0
        else -> scope.rules.attackDamage
    }

    /**
     * After a hit that didn't kill it, the creature may flee (docs/mechanics.md §7): each healthy crew
     * member here attacking it adds 1 if Confident plus its damage ÷ [Rules.fleeDamageDivisor]; it
     * flees with that many chances in 16, never on a 15. It jumps at once to a random neighboring
     * location, and each attacker gains [Rules.fleeMorale].
     */
    private fun maybeFlee(creature: Actor, striker: Actor, world: World, scope: TickScope) {
        if (creature.kind != ActorKind.CREATURE || !striker.isLoyalCrew) return
        val rules = scope.rules
        val attackers = world.occupants(creature.room).filter {
            it.isLoyalCrew && !it.isDown && (it.id == striker.id || it.current?.action == Action.Attack(creature.id))
        }
        val damageTaken = creature.maxHealth - creature.health
        val score = attackers.filter { it.health > rules.fleeHealthAbove }.sumOf {
            (if (MoraleSystem.effective(it, world, rules) >= rules.fleeConfidentMorale) 1 else 0) + damageTaken / rules.fleeDamageDivisor
        }
        if (score <= 0) return
        val roll = scope.roll(ODDS, "${creature.name} flees (needs under $score)")
        if (roll == ODDS - 1 || roll >= score) return

        // Any way out that's open, an open grille included.
        // Differs from the original: its random move never used the grille, only doors and ladders from
        // a room or duct links from a duct.
        val ways = world.room(creature.room).exits.filter {
            it.kind != LinkKind.GRILLE || Grilles.roomOf(creature.room, world)?.grilleRemoved == true
        }
        if (ways.isEmpty()) return
        val from = creature.room
        val to = ways[scope.roll(ways.size, "${creature.name} flees to")].to
        creature.current = null
        creature.queue.clear()
        creature.room = to
        val seenBy = Visibility.Perceived(world.witnesses(listOf(from, to)))
        scope.emit(GameEvent.CreatureFled(scope.tick, creature.id, attackers.map { it.id }, seenBy))
        scope.emit(GameEvent.ActorMoved(scope.tick, creature.id, from, to, seenBy))
        // The original's cap of 8 is on base morale, which there included the in-use item's bonus.
        attackers.forEach {
            val itemBonus = MoraleSystem.base(it, world, rules) - it.morale
            MoraleSystem.adjust(it, rules.fleeMorale, rules.moraleCap - itemBonus)
        }
    }

    /**
     * [target] goes down: crew collapse, the creature dies. They stop what they were doing and never
     * act again. What they held drops.
     */
    fun knockDown(target: Actor, world: World, scope: TickScope, seenBy: Visibility) {
        target.current = null
        target.queue.clear()
        listOfNotNull(target.inUse, target.carried).forEach { world.drop(target, it) }
        scope.emit(GameEvent.ActorDown(scope.tick, target.id, seenBy))
    }
}
