package org.tadams.nostromo.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Scenario

/** A grid cell across the whole map; rows run down through the deck bands. */
data class Cell(val col: Int, val row: Int)

/** One deck's horizontal strip of the map: [rows] rows starting at [firstRow]. Null [deck] is rooms with no deck. */
data class Band(val deck: String?, val firstRow: Int, val rows: Int)

/**
 * Where everything goes on the schematic map (docs/proto-map.md), in grid cells, not pixels.
 * Rooms get a [Cell] each; a duct segment joined to a room by a grille isn't on the grid but drawn
 * beside that room, so [ducts] maps it to the room.
 */
data class MapLayout(
    val rooms: Map<RoomId, Cell>,
    val ducts: Map<RoomId, RoomId>,
    val bands: List<Band>,
    val cols: Int,
    val rows: Int,
)

/**
 * Lays out [scenario]'s map automatically, using only its rooms, links and decks. Deterministic:
 * the same scenario always gives the same layout.
 *
 * Each deck is a band. Within a deck, rooms joined by doors and ladders are placed breadth-first
 * from the most-linked room, each in the free cell nearest the room it was reached from.
 */
fun layoutMap(scenario: Scenario): MapLayout {
    val kinds = scenario.rooms.associate { it.id to it.kind }
    val decks = scenario.rooms.associate { it.id to it.deck }

    // A duct sits beside the room its grille opens into.
    val ducts = LinkedHashMap<RoomId, RoomId>()
    for (link in scenario.links.filter { it.kind == LinkKind.GRILLE }) {
        val (duct, room) = when {
            kinds[link.from] == RoomKind.DUCT && kinds[link.to] == RoomKind.ROOM -> link.from to link.to
            kinds[link.to] == RoomKind.DUCT && kinds[link.from] == RoomKind.ROOM -> link.to to link.from
            else -> continue
        }
        if (duct !in ducts) ducts[duct] = room
    }

    // Everything else is a box on the grid, including any duct with no room of its own.
    val placed = scenario.rooms.map { it.id }.filter { it !in ducts }
    val deckOrder = placed.map { decks[it] }.distinct().sortedBy { it == null }

    val rooms = LinkedHashMap<RoomId, Cell>()
    val bands = mutableListOf<Band>()
    var firstRow = 0
    var cols = 0
    for (deck in deckOrder) {
        val members = placed.filter { decks[it] == deck }
        val memberSet = members.toSet()
        val edges = scenario.links.filter {
            (it.kind == LinkKind.DOOR || it.kind == LinkKind.LADDER) && it.from in memberSet && it.to in memberSet && it.from != it.to
        }
        val cells = layoutDeck(members, edges.map { it.from to it.to })
        val rows = cells.values.maxOf { it.row } + 1
        cells.forEach { (id, cell) -> rooms[id] = Cell(cell.col, cell.row + firstRow) }
        bands += Band(deck, firstRow, rows)
        firstRow += rows
        cols = max(cols, cells.values.maxOf { it.col } + 1)
    }
    return MapLayout(rooms, ducts, bands, cols, firstRow)
}

/** Places one deck's rooms on a grid, normalised so the smallest column and row are 0. */
private fun layoutDeck(members: List<RoomId>, edges: List<Pair<RoomId, RoomId>>): Map<RoomId, Cell> {
    val neighbours = members.associateWith { mutableListOf<RoomId>() }
    for ((a, b) in edges) {
        if (b !in neighbours.getValue(a)) neighbours.getValue(a) += b
        if (a !in neighbours.getValue(b)) neighbours.getValue(b) += a
    }

    val cells = LinkedHashMap<RoomId, Cell>()
    val taken = mutableSetOf<Cell>()
    fun place(id: RoomId, cell: Cell) {
        cells[id] = cell
        taken += cell
    }

    while (cells.size < members.size) {
        // Each separate group starts right of everything placed so far, at its most-linked room.
        val start = members.filter { it !in cells }.maxBy { neighbours.getValue(it).size }
        val startCol = if (cells.isEmpty()) 0 else cells.values.maxOf { it.col } + 2
        val startRow = if (cells.isEmpty()) 0 else cells.values.minOf { it.row }
        place(start, Cell(startCol, startRow))
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val room = queue.removeFirst()
            for (next in neighbours.getValue(room)) {
                if (next in cells) continue
                place(next, nearestFree(cells.getValue(room), taken))
                queue += next
            }
        }
    }

    val minCol = cells.values.minOf { it.col }
    val minRow = cells.values.minOf { it.row }
    return cells.mapValues { (_, c) -> Cell(c.col - minCol, c.row - minRow) }
}

/**
 * The free cell nearest [from]: right, down, left, up first, then the diagonals, then further rings,
 * each ring nearest-first and clockwise from the right.
 */
private fun nearestFree(from: Cell, taken: Set<Cell>): Cell {
    var ring = 1
    while (true) {
        val candidates = buildList {
            for (dx in -ring..ring) for (dy in -ring..ring) if (max(abs(dx), abs(dy)) == ring) add(dx to dy)
        }.sortedWith(compareBy({ abs(it.first) + abs(it.second) }, { clockwise(it.first, it.second) }))
        candidates.map { (dx, dy) -> Cell(from.col + dx, from.row + dy) }.firstOrNull { it !in taken }?.let { return it }
        ring++
    }
}

/** Angle from the right, clockwise on screen (rows grow downwards), in [0, 2π). */
private fun clockwise(dx: Int, dy: Int): Double {
    val angle = atan2(dy.toDouble(), dx.toDouble())
    return if (angle < 0) angle + 2 * PI else angle
}
