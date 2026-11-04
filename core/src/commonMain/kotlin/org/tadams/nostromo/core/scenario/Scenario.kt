package org.tadams.nostromo.core.scenario

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.model.ActorId
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
    val rules: Rules = Rules(),
)

/** A location on the map. */
@Serializable
data class RoomDef(
    val id: RoomId,
    val name: String,
    /** Which deck it's on, for map layout. Free text, e.g. "upper". */
    val deck: String? = null,
    val kind: RoomKind = RoomKind.ROOM,
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

/** What a link physically is. Grilles join a room to its duct; duct links join duct segments. */
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
)

/** Broad type of actor. Only [CREW] take orders. */
@Serializable
enum class ActorKind {
    @SerialName("crew") CREW,
    @SerialName("creature") CREATURE,
    @SerialName("animal") ANIMAL,
    // TODO Android?!?

}
