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
    /** Hidden roles handed out at the start: the android and the creature's host. Default: neither. */
    val roles: Roles = Roles(),
)

/**
 * A kind of item, e.g. "laser". Only an item *in use* counts, as a weapon, for morale, as a
 * detector or to catch an animal (docs/mechanics.md §4, §5, §7, §9).
 */
@Serializable
data class ItemTypeDef(
    val id: String,
    val name: String,
    /** Health an attack with this in use takes off the target. 0 means it does nothing. */
    val damage: Int = 0,
    /** Added to the holder's base morale while it's in use. */
    val morale: Int = 0,
    /** Attacks it has in it; each attack uses one. Null means unlimited. Original: laser 8, extinguisher 3, harpoon 1. */
    val charges: Int? = null,
    /** Whether it's destroyed once its charges are used up (the tracker, the net), rather than left empty. */
    val breaksWhenSpent: Boolean = false,
    /** Room damage, in percent, each time it's used to attack in a room (not a duct). Original: laser 30, harpoon 75. */
    val roomDamagePercent: Int = 0,
    /**
     * Whether a hit with it can drive the creature off though it does no damage (the extinguisher).
     * Hits that do damage always can.
     */
    val scares: Boolean = false,
    /** What an attack with it does instead of, or as well as, plain damage. */
    val effect: ItemEffect? = null,
    /** Whether it works as a motion detector while in use (the tracker). */
    val detects: Boolean = false,
    /** Whether it can fight fires while in use, one charge per use (the extinguisher). */
    val fightsFire: Boolean = false,
    /**
     * Whether it can catch an animal while in use, and how much easier it makes that (the cat box 0,
     * the net 4). Null if it can't.
     */
    val catchBonus: Int? = null,
)

/** A weapon's special effect (docs/mechanics.md §7). */
@Serializable
enum class ItemEffect {
    /** Freezes the target for [Rules.netFreezeSeconds] instead of hurting it (the net). */
    @SerialName("freeze") FREEZE,

    /**
     * As well as its damage: every crew member in the location goes down, the attacker included, and
     * every item there is destroyed (the harpoon).
     */
    @SerialName("killRoom") KILL_ROOM,
}

/** Who gets the hidden roles (docs/mechanics.md §8, docs/brainstorm.md §6). The host is chosen first; the android is never the host. */
@Serializable
data class Roles(
    /** The crew member who is secretly an android, revealed once the creature is hurt enough ([Rules.androidRevealDamage]). */
    val android: RoleChoice = RoleChoice(),
    /** The crew member the creature hatched from: out of play from the start, not a body. */
    val host: RoleChoice = RoleChoice(),
)

/** How one role is handed out among the crew. */
@Serializable
data class RoleChoice(
    val pick: RolePick = RolePick.NONE,
    /** For [RolePick.WEIGHTED]: each candidate's weight. Crew not listed can't get the role. */
    val weights: List<RoleWeight> = emptyList(),
)

@Serializable
enum class RolePick {
    /** Nobody gets the role. */
    @SerialName("none") NONE,

    /** Any crew member, equally likely. */
    @SerialName("any") ANY,

    /** One of [RoleChoice.weights], as likely as its weight. */
    @SerialName("weighted") WEIGHTED,
}

@Serializable
data class RoleWeight(val actor: ActorId, val weight: Int)

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
    /** What's special about this room: controls, airlocks, the shuttle, what can burn (docs/mechanics.md §3, §10). */
    val features: Set<RoomFeature> = emptySet(),
)

/** Special rooms (docs/mechanics.md §3, §7, §10). */
@Serializable
enum class RoomFeature {
    /** Crew here can arm and override the self-destruct. Original: the command center. */
    @SerialName("destructControls") DESTRUCT_CONTROLS,

    /** Crew here can blow and seal every [AIRLOCK]. Original: Corridor 6. */
    @SerialName("airlockControls") AIRLOCK_CONTROLS,

    /** Can be blown from the airlock controls, removing everything inside. */
    @SerialName("airlock") AIRLOCK,

    /** Where a creature that survives being blown out comes back in ("Intruder entering"). Original: the shuttle bay. */
    @SerialName("intruderEntry") INTRUDER_ENTRY,

    /** Crew here can go into hypersleep, leaving play safely. Original: the cryo vault. */
    @SerialName("hypersleep") HYPERSLEEP,

    /** Crew here can launch the shuttle. Original: the shuttle itself. */
    @SerialName("launch") LAUNCH,

    /** Catches fire at [Rules.fireAtPercent] damage. Original: the three engine rooms. */
    @SerialName("flammable") FLAMMABLE,

    /** A system the ship depends on; a failure warning at [Rules.fireAtPercent] damage. Original: command center, computer, life support, shuttle bay. */
    @SerialName("keySystem") KEY_SYSTEM,
}

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
    /**
     * How hard this crew member finds catching an animal: they need a roll of 0-15 at least this,
     * less the catching item's [ItemTypeDef.catchBonus]. Original: 13-15 (docs/mechanics.md §2, §9).
     */
    val catchDifficulty: Int = 14,
    /**
     * Base duration of a move through a grille, if it differs from this actor's other moves. The
     * speed penalty is still added. Original: the creature 50 ticks against 80 for its other moves.
     */
    val grilleMoveSeconds: Double? = null,
)

/**
 * Broad type of actor. Only [CREW] take orders. The android is crew with a hidden role
 * ([Roles.android]), not a kind of its own: it's crew until revealed.
 */
@Serializable
enum class ActorKind(
    /** What this kind of actor can do unless the scenario says otherwise. */
    val defaultCapabilities: Set<ActionType>,
) {
    @SerialName("crew") CREW(
        setOf(
            ActionType.MOVE, ActionType.ATTACK, ActionType.REMOVE_GRILLE,
            ActionType.GET, ActionType.DROP, ActionType.SWAP, ActionType.CATCH,
            ActionType.ARM_DESTRUCT, ActionType.DISARM_DESTRUCT, ActionType.BLOW_AIRLOCK, ActionType.SEAL_AIRLOCK,
            ActionType.HYPERSLEEP, ActionType.LAUNCH, ActionType.FIGHT_FIRE,
        ),
    ),
    @SerialName("creature") CREATURE(setOf(ActionType.MOVE, ActionType.ATTACK, ActionType.REMOVE_GRILLE, ActionType.PROWL)),
    @SerialName("animal") ANIMAL(setOf(ActionType.MOVE)),
}
