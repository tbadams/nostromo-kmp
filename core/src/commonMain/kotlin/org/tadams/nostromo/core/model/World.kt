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
        if (!release(actor, id)) return
        item(id).room = actor.room
    }

    /**
     * Destroys item [id], wherever it is. An animal caught in it is lost with it (goes down).
     * Returns the animal, if there was one.
     */
    fun destroy(id: ItemId): Actor? {
        actors.values.forEach { release(it, id) }
        val item = item(id)
        item.room = null
        item.destroyed = true
        val animal = item.caught?.let { actors.getValue(it) }
        animal?.health = 0
        return animal
    }

    /** Takes [id] out of [actor]'s hands; false if they don't hold it. */
    private fun release(actor: Actor, id: ItemId): Boolean {
        when (id) {
            actor.inUse -> actor.inUse = null
            actor.carried -> actor.carried = null
            else -> return false
        }
        return true
    }

    /** Every actor currently in [room], down or not. */
    fun occupants(room: RoomId): List<Actor> = actors.values.filter { it.room == room }

    /** Crew who can see and hear what happens in [rooms]: loyal crew standing in one of them, and up. */
    fun witnesses(rooms: Collection<RoomId>): Set<ActorId> =
        actors.values.filter { it.isLoyalCrew && !it.isDown && it.room in rooms }.mapTo(mutableSetOf()) { it.id }

    /** The item [actor] has in use, if any. */
    fun inUse(actor: Actor): Item? = actor.inUse?.let(::item)

    /** Moves each caught animal to wherever its item is: with its holder, or where it lies. */
    fun carryCaught() {
        for (item in items.values) {
            val animal = item.caught?.let { actors.getValue(it) } ?: continue
            (item.room ?: holderOf(item)?.room)?.let { animal.room = it }
        }
    }

    /** Who holds [item], in use or carried; null if nobody does. */
    fun holderOf(item: Item): Actor? = actors.values.firstOrNull { it.inUse == item.id || it.carried == item.id }

    companion object {

        /**
         * Builds the starting world from a validated [scenario]. [host] (the creature hatched from them)
         * is left out of play; [android] is secretly an android.
         */
        fun from(scenario: Scenario, host: ActorId? = null, android: ActorId? = null): World {
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
            val actors = scenario.actors.filter { it.id != host }.associate {
                it.id to Actor(
                    it.id, it.name, it.kind, it.room, it.brain, it.speedPenaltySeconds, it.actionSeconds, it.health,
                    capabilities = it.capabilities ?: it.kind.defaultCapabilities,
                    morale = it.morale,
                    isAndroid = it.id == android,
                    catchDifficulty = it.catchDifficulty,
                    grilleMoveSeconds = it.grilleMoveSeconds,
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
     * Structural damage, 0-100%. The creature's prowling and weapons (laser, harpoon) add to it.
     * TODO room damage consequences (build step 5; mechanics.md §3): system failure warnings and
     *  engine fires at 30%, a "structural damage" warning at 70%, the ship exploding at 100%. Also
     *  fire spreading.
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
    /** Id of the AI driving this actor when idle. The android gets one when it's revealed. */
    var brain: String?,
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
    /** Secretly an android (docs/mechanics.md §8). Crew in every way until [revealed]. */
    val isAndroid: Boolean = false,
    /** See [org.tadams.nostromo.core.scenario.ActorDef.catchDifficulty]. */
    val catchDifficulty: Int = 14,
    /** See [org.tadams.nostromo.core.scenario.ActorDef.grilleMoveSeconds]. */
    val grilleMoveSeconds: Double? = null,
) {
    /** Health at the start; the creature's damage taken is this minus [health]. */
    val maxHealth: Int = health

    /** Whether this android has turned on the crew. It then acts on its own and takes no orders. */
    var revealed: Boolean = false

    /** Netted: this actor does nothing until this tick. */
    var frozenUntil: Long = 0

    /** For an animal: the item it's been caught in, or null if it's loose. A caught animal goes where its holder goes. */
    var caughtIn: ItemId? = null

    /** Whether this actor obeys orders: crew who are up and not a revealed android. */
    val takesOrders get() = isCrew && !isDown && !revealed

    /** On the crew's side: crew, except a revealed android. Only these count for morale, witnesses and the ending. */
    val isLoyalCrew get() = isCrew && !revealed

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

    /** Of the crew kind, the android included even once revealed. See [isLoyalCrew] for whose side they're on. */
    val isCrew get() = kind == ActorKind.CREW

    /** Who knows about this actor's own actions: themselves if they're loyal crew, otherwise nobody. */
    fun selfVisibility(): Visibility = if (isLoyalCrew) Visibility.Perceived(setOf(id)) else Visibility.Hidden
}

/** One item. [room] is where it lies, or null while someone holds it (or it's [destroyed]). */
class Item(val id: ItemId, val type: ItemTypeDef, var room: RoomId?) {
    /** Uses left, or null for unlimited. */
    var charges: Int? = type.charges

    /** Gone for good: used up, or blown apart with everything else in a room. */
    var destroyed: Boolean = false

    /** The animal caught in it, if any. */
    var caught: ActorId? = null

    /** Whether it has no uses left (never true for unlimited items). */
    val isEmpty get() = charges == 0
}

/** An action in progress, counting down from [totalTicks]. */
class ScheduledAction(val action: Action, val totalTicks: Int) {
    var remainingTicks = totalTicks
    val elapsedTicks get() = totalTicks - remainingTicks
}
