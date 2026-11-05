package org.tadams.nostromo.game

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Scenario

class MapLayoutTest {
    private fun id(s: String) = RoomId(s)
    private fun room(s: String, deck: String? = "main", kind: RoomKind = RoomKind.ROOM) = RoomDef(id(s), s, deck, kind)
    private fun link(a: String, b: String, kind: LinkKind = LinkKind.DOOR) = LinkDef(id(a), id(b), kind)
    private fun scenario(rooms: List<RoomDef>, links: List<LinkDef>) =
        Scenario(id = "t", name = "T", rooms = rooms, links = links, actors = emptyList())

    @Test
    fun chainOnOneDeckIsAdjacent() {
        val layout = layoutMap(scenario(listOf(room("a"), room("b"), room("c")), listOf(link("a", "b"), link("b", "c"))))
        val (a, b, c) = listOf("a", "b", "c").map { layout.rooms.getValue(id(it)) }
        assertEquals(1, distance(a, b))
        assertEquals(1, distance(b, c))
    }

    @Test
    fun ductsSitOnTheirRoomNotTheGrid() {
        val layout = layoutMap(scenario(
            listOf(room("a"), room("b"), room("a-duct", kind = RoomKind.DUCT), room("b-duct", kind = RoomKind.DUCT)),
            listOf(link("a", "b"), link("a", "a-duct", LinkKind.GRILLE), link("b", "b-duct", LinkKind.GRILLE),
                link("a-duct", "b-duct", LinkKind.DUCT)),
        ))
        assertEquals(mapOf(id("a-duct") to id("a"), id("b-duct") to id("b")), layout.ducts)
        assertEquals(setOf(id("a"), id("b")), layout.rooms.keys)
    }

    @Test
    fun decksAreBandsAndNoDeckGoesLast() {
        val layout = layoutMap(scenario(
            listOf(room("loose", deck = null), room("u1", "upper"), room("u2", "upper"), room("l1", "lower")),
            listOf(link("u1", "u2"), link("u1", "l1", LinkKind.LADDER)),
        ))
        assertEquals(listOf("upper", "lower", null), layout.bands.map { it.deck })
        val decks = mapOf("u1" to "upper", "u2" to "upper", "l1" to "lower", "loose" to null)
        decks.forEach { (room, deck) -> assertInBand(layout, id(room), deck) }
    }

    @Test
    fun separateGroupsDontOverlap() {
        val layout = layoutMap(scenario(listOf(room("a"), room("b"), room("c"), room("d")), listOf(link("a", "b"), link("c", "d"))))
        assertEquals(4, layout.rooms.values.toSet().size)
    }

    @Test
    fun sameScenarioSameLayout() {
        val s = scenario(
            listOf(room("hub"), room("n"), room("e"), room("s"), room("w"), room("x")),
            listOf(link("hub", "n"), link("hub", "e"), link("hub", "s"), link("hub", "w"), link("hub", "x")),
        )
        assertEquals(layoutMap(s), layoutMap(s))
        assertEquals(6, layoutMap(s).rooms.values.toSet().size)
    }

    private fun assertInBand(layout: MapLayout, room: RoomId, deck: String?) {
        val band = layout.bands.single { it.deck == deck }
        val row = layout.rooms.getValue(room).row
        assertTrue(row in band.firstRow until band.firstRow + band.rows, "$room row $row not in $band")
    }

    private fun distance(a: Cell, b: Cell) = abs(a.col - b.col) + abs(a.row - b.row)
}
