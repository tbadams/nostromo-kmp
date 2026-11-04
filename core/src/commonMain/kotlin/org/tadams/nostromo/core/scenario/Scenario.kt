package org.tadams.nostromo.core.scenario

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId

/**
 * Everything needed to start a game, loaded from JSON (see [ScenarioLoader]).
 *
 * The map is one graph of locations. Duct segments are locations too ([RoomKind.DUCT]),
 * joined to their room by a [LinkKind.GRILLE] link and to each other by [LinkKind.DUCT] links.
 */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val rooms: List<RoomDef>,
    val links: List<LinkDef>,
    val actors: List<ActorDef>,
    /** The kinds of item there are; [items] refer to them by id. */
    val itemTypes: List<ItemTypeDef> = emptyList(),
    /** Every item on the ship, and where it starts. */
    val items: List<ItemDef> = emptyList(),
    val rules: Rules = Rules(),
)

/**
 * A kind of item, e.g. "laser". Only an item *in use* counts, as a weapon and for morale
 * (docs/mechanics.md §4, §5, §7).
 * TODO charges (laser 8, extinguisher 3, harpoon 1, tracker breaks) and special effects (room
 *  damage, the harpoon killing the room, the net freezing, scaring the creature off): build step 4.
 */
@Serializable
data class ItemTypeDef(
    val id: String,
    val name: String,
    /** Health an attack with this in use takes off the target. 0 means it does nothing. */
    val damage: Int = 0,
    /** Added to the holder's base morale while it's in use. */
    val morale: Int = 0,
)

/** One item, of [type] (an [ItemTypeDef.id]), lying in [room] at the start. */
@Serializable
data class ItemDef(
    val id: ItemId,
    val type: String,
    val room: RoomId,
)

/** A location on the map. */
@Serializable
data class RoomDef(
    val id: RoomId,
    val name: String,
    /** Which deck it's on, for map layout. Free text, e.g. "upper". */
    val deck: String? = null,
    val kind: RoomKind = RoomKind.ROOM,
    /**
     * Whether this room's grille (its [LinkKind.GRILLE] link to its duct) starts removed. Grilles in
     * place block the way until someone removes them. Original: all in place except Corridor 6's.
     */
    val grilleRemoved: Boolean = false,
)

/** Whether a location is a normal room or a duct segment. */
@Serializable
enum class RoomKind {
    @SerialName("room") ROOM,
    @SerialName("duct") DUCT,
}

/** A connection between two locations. Two-way unless [oneWay]. */
@Serializable
data class LinkDef(
    val from: RoomId,
    val to: RoomId,
    val kind: LinkKind = LinkKind.DOOR,
    val oneWay: Boolean = false,
)

/**
 * What a link physically is. Grilles join a room to its duct (at most one each) and block the way
 * while in place; duct links join duct segments.
 */
@Serializable
enum class LinkKind {
    @SerialName("door") DOOR,
    @SerialName("ladder") LADDER,
    @SerialName("grille") GRILLE,
    @SerialName("duct") DUCT,
}

/** Someone or something in the scenario, and where they start. */
@Serializable
data class ActorDef(
    val id: ActorId,
    val name: String,
    val kind: ActorKind,
    val room: RoomId,
    /** Id of the AI that drives this actor when idle, e.g. "wumpus". Null for crew. */
    val brain: String? = null,
    /** Hits this actor can take before going down (crew collapse; the creature dies). */
    // TODO abstract into stats
    val health: Int = 4,
    /** Added to every action this actor takes: their personal slowness. */
    // TODO abstract into stats
    // TODO convert to tick based, refactoring surrounding code
    val speedPenaltySeconds: Double = 0.0,
    /**
     * This actor's own base duration for some actions, replacing [Rules.actionSeconds] for them.
     * [speedPenaltySeconds] is still added. E.g. each crew member's grille-removal time.
     */
    val actionSeconds: Map<ActionType, Double> = emptyMap(),
    /**
     * Which types of action this actor can ever take. Null means the default for its [kind]
     * ([ActorKind.defaultCapabilities]).
     */
    val capabilities: Set<ActionType>? = null,
    /** Starting base morale (docs/mechanics.md §5). Only crew have morale. Original: 3 or 4. */
    val morale: Int = 3,
)

/** Broad type of actor. Only [CREW] take orders. */
@Serializable
enum class ActorKind(
    /** What this kind of actor can do unless the scenario says otherwise. */
    val defaultCapabilities: Set<ActionType>,
) {
    @SerialName("crew") CREW(
        setOf(
            ActionType.MOVE, ActionType.ATTACK, ActionType.REMOVE_GRILLE,
            ActionType.GET, ActionType.DROP, ActionType.SWAP,
        ),
    ),
    @SerialName("creature") CREATURE(setOf(ActionType.MOVE, ActionType.ATTACK, ActionType.REMOVE_GRILLE, ActionType.PROWL)),
    @SerialName("animal") ANIMAL(setOf(ActionType.MOVE)),
    // TODO Android?!?

}
