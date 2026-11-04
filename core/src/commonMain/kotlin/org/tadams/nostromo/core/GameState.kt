package org.tadams.nostromo.core

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.ActorKind
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
                RoomState(room.id, room.name, room.deck, room.kind, room.exits.map { it.to }, room.damagePercent)
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
                    morale = if (actor.isCrew) MoraleSystem.effective(actor, world, rules) else null,
                )
            },
            items = world.items.values.map { ItemState(it.id, it.type.name, it.room) },
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
) {
    val isDown get() = health <= 0
}

/** An item as the UI sees it. [room] is where it lies, or null if someone holds it. */
data class ItemState(val id: ItemId, val name: String, val room: RoomId?)

/** An action in progress: [elapsedTicks] of [totalTicks] done. */
data class ActivityState(val action: Action, val elapsedTicks: Int, val totalTicks: Int) {
    /** 0.0 at the start, approaching 1.0 as it finishes. */
    val progress: Float get() = elapsedTicks.toFloat() / totalTicks
}
