package org.tadams.nostromo.core

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.Grilles
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.systems.MoraleSystem

/** Immutable snapshot of the game for the UI. */
data class GameState(
    /** Ticks completed so far. */
    val tick: Long,
    /** How the game ended, or null while it's still going. */
    val result: GameResult?,
    /** In scenario order. */
    val rooms: List<RoomState>,
    /** In scenario order. */
    val actors: List<ActorState>,
    /** In scenario order, held ones included. */
    val items: List<ItemState> = emptyList(),
    /** Ship-wide status for the status bar. */
    val ship: ShipState = ShipState(),
) {
    /**
     * Lookup actor state by ID.
     */
    fun actor(id: ActorId): ActorState = actors.first { it.id == id }

    /** Lookup room state by ID. */
    fun room(id: RoomId): RoomState = rooms.first { it.id == id }

    /** Lookup item state by ID. */
    fun item(id: ItemId): ItemState = items.first { it.id == id }

    /** Items lying in [room]. */
    fun itemsIn(room: RoomId): List<ItemState> = items.filter { it.room == room }

    companion object {
        /**
         * Returns an immutable snapshot of the active game World, specifying the current time tick.
         */
        internal fun of(world: World, rules: Rules, tick: Long, result: GameResult?) = GameState(
            tick = tick,
            result = result,
            rooms = world.rooms.values.map { room ->
                RoomState(
                    room.id, room.name, room.deck, room.kind, room.exits.map { it.to }, room.damagePercent,
                    grilleRemoved = Grilles.roomOf(room.id, world)?.grilleRemoved,
                    passages = room.exits.filter { it.kind != LinkKind.GRILLE }.map { it.to },
                    burning = room.burning,
                    structural = room.damagePercent >= rules.structuralAtPercent,
                    blown = room.blown,
                )
            },
            actors = world.actors.values.map { actor ->
                ActorState(
                    id = actor.id,
                    name = actor.name,
                    kind = actor.kind,
                    room = actor.room,
                    health = actor.health,
                    activity = actor.current?.let { ActivityState(it.action, it.elapsedTicks, it.totalTicks) },
                    queued = actor.queue.toList(),
                    inUse = actor.inUse,
                    carried = actor.carried,
                    morale = if (actor.isLoyalCrew) MoraleSystem.effective(actor, world, rules) else null,
                    bodyFound = actor.bodyFound,
                    takesOrders = actor.takesOrders,
                    caught = actor.caughtIn != null,
                    frozen = actor.frozenUntil > tick,
                    asleep = actor.asleep,
                    gone = actor.gone,
                )
            },
            items = world.items.values.filterNot { it.destroyed }.map { ItemState(it.id, it.type.name, it.room, it.charges) },
            ship = ShipState(
                oxygen = world.ship.oxygen.toInt(),
                oxygenCritical = rules.oxygenUsed && world.ship.oxygen <= rules.oxygenCriticalAt,
                destructTicksLeft = world.ship.destructTicksLeft,
            ),
        )
    }
}

/** A location as the UI sees it. [exits] are the locations reachable from here. */
data class RoomState(
    val id: RoomId,
    val name: String,
    val deck: String?,
    val kind: RoomKind,
    val exits: List<RoomId>,
    /** Structural damage, 0-100%. */
    val damagePercent: Int,
    /** Whether this location's grille (to its duct, or for a duct to its room) is off; null if it has none. */
    val grilleRemoved: Boolean? = null,
    /** [exits] not through a grille: doors and ladders from a room, duct links from a duct. */
    val passages: List<RoomId> = emptyList(),
    /** On fire. */
    val burning: Boolean = false,
    /** At or past the structural damage warning. */
    val structural: Boolean = false,
    /** An airlock that's been blown and not re-sealed. */
    val blown: Boolean = false,
)

/**
 * Ship-wide status. [oxygen] is in the original's units (starts at 7500); [destructTicksLeft] is null
 * while the self-destruct isn't armed. Show times as real time with [Time.clock].
 */
data class ShipState(
    val oxygen: Int = 0,
    val oxygenCritical: Boolean = false,
    val destructTicksLeft: Long? = null,
)

/** A character, creature or animal as the UI sees it. */
data class ActorState(
    val id: ActorId,
    val name: String,
    val kind: ActorKind,
    val room: RoomId,
    /** Hits left; 0 means down. */
    val health: Int,
    /** What they're doing now, or null if idle. */
    val activity: ActivityState?,
    /** Actions waiting after the current one, e.g. the rest of a click-to-move path. */
    val queued: List<Action>,
    /** The item in use, if any. */
    val inUse: ItemId? = null,
    /** The carried spare, if any. */
    val carried: ItemId? = null,
    /** Effective morale (docs/mechanics.md §5); null for non-crew. */
    val morale: Int? = null,
    /** For crew who are down: whether anyone has found them. Until then the player shouldn't be told. */
    val bodyFound: Boolean = false,
    /**
     * Whether orders to them do anything: false once down, or once revealed as the android. Revealed
     * androids aren't otherwise flagged here, so the UI can't give the secret away; the player
     * learns it from what crew see it do.
     */
    val takesOrders: Boolean = false,
    /** For an animal: caught in a box or net, traveling with whoever holds it. */
    val caught: Boolean = false,
    /** Netted and unable to act for now. */
    val frozen: Boolean = false,
    /** In hypersleep: out of play, safe. */
    val asleep: Boolean = false,
    /** Lost from the ship (blown out of an airlock). */
    val gone: Boolean = false,
) {
    val isDown get() = health <= 0
}

/**
 * An item as the UI sees it. [room] is where it lies, or null if someone holds it. [charges] is uses
 * left, or null for unlimited. Destroyed items aren't listed.
 */
data class ItemState(val id: ItemId, val name: String, val room: RoomId?, val charges: Int? = null)

/** An action in progress: [elapsedTicks] of [totalTicks] done. */
data class ActivityState(val action: Action, val elapsedTicks: Int, val totalTicks: Int) {
    /** 0.0 at the start, approaching 1.0 as it finishes. */
    val progress: Float get() = elapsedTicks.toFloat() / totalTicks
}
