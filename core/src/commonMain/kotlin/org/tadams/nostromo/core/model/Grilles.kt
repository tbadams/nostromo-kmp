package org.tadams.nostromo.core.model

import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomKind

/** Finding a location's grille. Grille state lives on the room side ([Room.grilleRemoved]). */
internal object Grilles {

    /**
     * The room whose grille [location] has: [location] itself if it's a room with a grille, the room
     * a duct's grille leads to, or null if [location] has no grille.
     */
    fun roomOf(location: RoomId, world: World): Room? {
        val here = world.room(location)
        val other = here.exits.firstOrNull { it.kind == LinkKind.GRILLE }?.to ?: return null
        return if (here.kind == RoomKind.ROOM) here else world.room(other)
    }

    /** Both sides of [room]'s grille: the room and its duct. */
    fun sides(room: Room): List<RoomId> =
        listOf(room.id) + room.exits.filter { it.kind == LinkKind.GRILLE }.map { it.to }
}
