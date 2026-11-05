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
 * from the most-linked room, each in the free cell nearest the room it was reached from. Among the
 * nearest free cells it takes the one where fewest lines would run through a room or lie along
 * another line (crossings are fine), and each band is shifted sideways the same way, for the
 * ladders between decks.
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

    val placedSet = placed.toSet()
    // The lines drawn between boxes: doors and ladders, on a deck or between decks.
    val lines = scenario.links
        .filter { (it.kind == LinkKind.DOOR || it.kind == LinkKind.LADDER) && it.from in placedSet && it.to in placedSet && it.from != it.to }
        .map { it.from to it.to }

    // Each deck on its own grid first; then stack them, which needs the widest.
    val decked = deckOrder.map { deck ->
        val members = placed.filter { decks[it] == deck }
        val memberSet = members.toSet()
        deck to layoutDeck(members, lines.filter { (a, b) -> a in memberSet && b in memberSet })
    }
    val cols = decked.maxOf { (_, cells) -> cells.values.maxOf { it.col } + 1 }

    val rooms = LinkedHashMap<RoomId, Cell>()
    val bands = mutableListOf<Band>()
    var firstRow = 0
    for ((deck, cells) in decked) {
        val rows = cells.values.maxOf { it.row } + 1
        val width = cells.values.maxOf { it.col } + 1
        fun shifted(offset: Int) = cells.mapValues { (_, c) -> Cell(c.col + offset, c.row + firstRow) }
        // Shift the band sideways to where its lines to the bands above lie along nothing.
        val offset = (0..cols - width).minBy { badLines(rooms + shifted(it), lines) }
        rooms += shifted(offset)
        bands += Band(deck, firstRow, rows)
        firstRow += rows
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
                val candidates = nearestFree(cells.getValue(room), taken)
                place(next, candidates.minBy { badLines(cells + (next to it), edges) })
                queue += next
            }
        }
    }

    val minCol = cells.values.minOf { it.col }
    val minRow = cells.values.minOf { it.row }
    return cells.mapValues { (_, c) -> Cell(c.col - minCol, c.row - minRow) }
}

/**
 * The free cells nearest [from]: the first ring around it with any free, in order of preference
 * (right, down, left, up, then the diagonals, nearest-first and clockwise from the right).
 */
private fun nearestFree(from: Cell, taken: Set<Cell>): List<Cell> {
    var ring = 1
    while (true) {
        val free = buildList {
            for (dx in -ring..ring) for (dy in -ring..ring) if (max(abs(dx), abs(dy)) == ring) add(dx to dy)
        }.sortedWith(compareBy({ abs(it.first) + abs(it.second) }, { clockwise(it.first, it.second) }))
            .map { (dx, dy) -> Cell(from.col + dx, from.row + dy) }
            .filter { it !in taken }
        if (free.isNotEmpty()) return free
        ring++
    }
}

/**
 * How many [lines] between rooms placed at [cells] would be misleading: one running through
 * another room's cell, or two lying along each other. Crossings don't count; they read fine.
 * Lines to rooms not placed yet are ignored.
 */
internal fun badLines(cells: Map<RoomId, Cell>, lines: List<Pair<RoomId, RoomId>>): Int {
    val drawn = lines.mapNotNull { (a, b) -> cells[a]?.let { ca -> cells[b]?.let { cb -> Triple(a, b, ca to cb) } } }
    var bad = 0
    for ((a, b, ends) in drawn) {
        bad += cells.count { (id, cell) -> id != a && id != b && cell.strictlyOn(ends.first, ends.second) }
    }
    for (i in drawn.indices) for (j in i + 1 until drawn.size) {
        if (alongEachOther(drawn[i].third, drawn[j].third)) bad++
    }
    return bad
}

private fun cross(o: Cell, a: Cell, b: Cell) = (a.col - o.col) * (b.row - o.row) - (a.row - o.row) * (b.col - o.col)

/** Whether this cell is on the segment from [a] to [b], not at either end. */
private fun Cell.strictlyOn(a: Cell, b: Cell) = this != a && this != b && cross(a, b, this) == 0 &&
    col in minOf(a.col, b.col)..maxOf(a.col, b.col) && row in minOf(a.row, b.row)..maxOf(a.row, b.row)

/** Whether two segments lie on one line and share more than a point. */
private fun alongEachOther(s: Pair<Cell, Cell>, t: Pair<Cell, Cell>): Boolean {
    val (a, b) = s
    val (c, d) = t
    if (cross(a, b, c) != 0 || cross(a, b, d) != 0) return false
    // Compare along whichever axis the line isn't constant in.
    val key: (Cell) -> Int = if (a.col != b.col) Cell::col else Cell::row
    val (lo1, hi1) = minOf(key(a), key(b)) to maxOf(key(a), key(b))
    val (lo2, hi2) = minOf(key(c), key(d)) to maxOf(key(c), key(d))
    return minOf(hi1, hi2) > maxOf(lo1, lo2)
}

/** Angle from the right, clockwise on screen (rows grow downwards), in [0, 2π). */
private fun clockwise(dx: Int, dy: Int): Double {
    val angle = atan2(dy.toDouble(), dx.toDouble())
    return if (angle < 0) angle + 2 * PI else angle
}
