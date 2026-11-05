package org.tadams.nostromo.core.model

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.ItemTypeDef
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Scenario

/** The mutable world, owned by the Simulation. The UI sees it only through immutable [org.tadams.nostromo.core.GameState] snapshots. */
class World(
    val rooms: Map<RoomId, Room>,
    /** In scenario order; systems iterate in this order, which keeps runs deterministic. */
    val actors: Map<ActorId, Actor>,
    /** In scenario order. */
    val items: Map<ItemId, Item> = emptyMap(),
) {
    /** The room with [id]; it must exist. */
    fun room(id: RoomId): Room = rooms.getValue(id)

    /** The item with [id]; it must exist. */
    fun item(id: ItemId): Item = items.getValue(id)

    /** Items lying on the floor of [room], in scenario order. */
    fun itemsIn(room: RoomId): List<Item> = items.values.filter { it.room == room }

    /** Takes item [id] off [actor] and puts it down where they are. Does nothing if they don't hold it. */
    fun drop(actor: Actor, id: ItemId) {
        when (id) {
            actor.inUse -> actor.inUse = null
            actor.carried -> actor.carried = null
            else -> return
        }
        item(id).room = actor.room
    }

    /** Every actor currently in [room], down or not. */
    fun occupants(room: RoomId): List<Actor> = actors.values.filter { it.room == room }

    /** Crew who can see and hear what happens in [rooms]: those standing in one of them, and up. */
    fun witnesses(rooms: Collection<RoomId>): Set<ActorId> =
        actors.values.filter { it.isCrew && !it.isDown && it.room in rooms }.mapTo(mutableSetOf()) { it.id }

    companion object {

        /** Builds the starting world from a validated [scenario]. */
        fun from(scenario: Scenario): World {
            val exits = scenario.rooms.associate { it.id to mutableListOf<Exit>() }
            for (link in scenario.links) {
                exits.getValue(link.from) += Exit(link.to, link.kind)
                if (!link.oneWay) exits.getValue(link.to) += Exit(link.from, link.kind)
            }
            val rooms = scenario.rooms.associate {
                it.id to Room(it.id, it.name, it.deck, it.kind, exits.getValue(it.id).distinct()).apply {
                    grilleRemoved = it.grilleRemoved
                }
            }
            val actors = scenario.actors.associate {
                it.id to Actor(
                    it.id, it.name, it.kind, it.room, it.brain, it.speedPenaltySeconds, it.actionSeconds, it.health,
                    capabilities = it.capabilities ?: it.kind.defaultCapabilities,
                    morale = it.morale,
                )
            }
            val types = scenario.itemTypes.associateBy { it.id }
            val items = scenario.items.associate { it.id to Item(it.id, types.getValue(it.type), it.room) }
            return World(rooms, actors, items)
        }
    }
}

/** A way out of a room, to [to], of the given [kind]. */
data class Exit(val to: RoomId, val kind: LinkKind)

/** A location: a room or a duct segment. */
class Room(
    val id: RoomId,
    val name: String,
    val deck: String?,
    val kind: RoomKind,
    val exits: List<Exit>,
) {
    /**
     * Structural damage, 0-100%. Only the creature's prowling adds to it so far.
     * TODO room damage consequences (build step 5; mechanics.md §3): system failure warnings and
     *  engine fires at 30%, a "structural damage" warning at 70%, the ship exploding at 100%. Also
     *  weapon damage (laser +30%, harpoon +75%) and fire spreading.
     */
    var damagePercent: Int = 0

    /** Whether this room's grille to its duct is off. Only meaningful for rooms with a grille. */
    var grilleRemoved: Boolean = false
}

/** Anyone or anything that can act: crew, creature, animal. */
class Actor(
    val id: ActorId,
    val name: String,
    val kind: ActorKind,
    var room: RoomId,
    val brain: String?,
    // TODO Abstract into stats
    /** Added to every action this actor takes. */
    val speedPenaltySeconds: Double,
    /** This actor's own base durations for some actions; see [org.tadams.nostromo.core.scenario.ActorDef.actionSeconds]. */
    val actionSeconds: Map<ActionType, Double>,
    /** Hits left before going down. */
    var health: Int,
    /** The types of action this actor can ever take. */
    val capabilities: Set<ActionType>,
    /**
     * Base morale before the in-use item's bonus. The rules for it, and for effective morale, are in
     * [org.tadams.nostromo.core.systems.MoraleSystem].
     */
    var morale: Int = 0,
) {
    /** The item in use: the one that counts as a weapon and for morale. */
    var inUse: ItemId? = null

    /** The spare, carried but not in use. */
    var carried: ItemId? = null

    /** Out of action: collapsed crew, a dead creature. Down actors stay where they fell and do nothing. */
    val isDown get() = health <= 0

    /** For a crew member who is down: whether anyone has found the body yet ([org.tadams.nostromo.core.systems.MoraleSystem]). */
    var bodyFound: Boolean = false

    /** What they're doing now, or null if idle. */
    var current: ScheduledAction? = null

    /** Actions to start, in order, once [current] finishes. */
    val queue = ArrayDeque<Action>()

    // TODO Include Android? Better name?
    val isCrew get() = kind == ActorKind.CREW

    /** Who knows about this actor's own actions: themselves if they're crew, otherwise nobody. */
    fun selfVisibility(): Visibility = if (isCrew) Visibility.Perceived(setOf(id)) else Visibility.Hidden
}

/** One item. [room] is where it lies, or null while someone holds it. */
class Item(val id: ItemId, val type: ItemTypeDef, var room: RoomId?)

/** An action in progress, counting down from [totalTicks]. */
class ScheduledAction(val action: Action, val totalTicks: Int) {
    var remainingTicks = totalTicks
    val elapsedTicks get() = totalTicks - remainingTicks
}
