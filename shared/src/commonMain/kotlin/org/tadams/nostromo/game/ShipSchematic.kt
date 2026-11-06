package org.tadams.nostromo.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.RoomState
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.ui.Amber
import org.tadams.nostromo.ui.Background
import org.tadams.nostromo.ui.DimGreen
import org.tadams.nostromo.ui.Green

/**
 * The ship as a schematic: boxes for rooms, lines for doors and ladders, a small marker beside each
 * room for its duct segment, laid out by [layoutMap] (docs/proto-map.md). Shows the same things as
 * the text list ([MapView]), in the same colours.
 *
 * Tapping where the selected crew member can move (a cyan room or duct) orders the move, the
 * same as its button in the action bar. Tapping any other room or duct with crew in it, bar the
 * selected crew member's own, selects the first of them.
 */
@Composable
internal fun ShipSchematic(ui: GameUiState, onOrder: (Action) -> Unit, onSelect: (ActorId) -> Unit, modifier: Modifier) {
    val layout = remember(ui.scenario) { layoutMap(ui.scenario) }
    val measurer = rememberTextMeasurer()
    val view = MapView(ui)
    val rooms = ui.game.rooms.associateBy { it.id }
    // Read the latest state from inside the long-lived tap handler.
    val currentUi by rememberUpdatedState(ui)
    val currentOnOrder by rememberUpdatedState(onOrder)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Canvas(
        modifier.border(1.dp, DimGreen).padding(8.dp).pointerInput(layout) {
            detectTapGestures { tap ->
                val geometry = Geometry(layout, size.toSize(), labelHeight = 16.dp.toPx())
                val minTarget = 24.dp.toPx()
                // Only the cyan places, and only as moves on offer right now, so a tap can't order
                // anything the action bar wouldn't. (Through an open grille is offered but not cyan.)
                val tapView = MapView(currentUi)
                val move = currentUi.actions.map { it.action }.filterIsInstance<Action.Move>()
                    .filter { it.to in tapView.destinations }
                    .firstOrNull { geometry.bounds(it.to, minTarget)?.contains(tap) == true }
                if (move != null) {
                    currentOnOrder(move)
                    return@detectTapGestures
                }
                // Anywhere else shown on the map, bar the selected crew member's own room: select the
                // first crew member there who answers, in crew tab order. Ducts first, as their tap area
                // reaches over their room box's corner.
                val tapped = (layout.ducts.keys + layout.rooms.keys)
                    .mapNotNull { id -> currentUi.game.rooms.firstOrNull { it.id == id } }
                    .filter { tapView.shown(it) && it.id != tapView.selectedRoom }
                    .firstOrNull { geometry.bounds(it.id, minTarget)?.contains(tap) == true }
                    ?: return@detectTapGestures
                tapView.visibleIn(tapped)
                    .firstOrNull { it.kind == ActorKind.CREW && condition(it, currentUi) == Condition.UP }
                    ?.let { currentOnSelect(it.id) }
            }
        },
    ) {
        val geometry = Geometry(layout, size, labelHeight = 16.dp.toPx())
        fun visible(id: RoomId) = rooms[id]?.let(view::shown) == true
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        val thin = 1.dp.toPx()

        // Deck labels in the gutter above each band.
        layout.bands.forEachIndexed { index, band ->
            drawLabel(measurer, (band.deck ?: "?").uppercase(), Offset(0f, geometry.bandTop(index)), Amber, bold = true)
        }

        // Links first, so the boxes sit on top of them.
        for (link in ui.scenario.links) {
            if (!visible(link.from) || !visible(link.to)) continue
            val a = geometry.centre(link.from) ?: continue
            val b = geometry.centre(link.to) ?: continue
            when (link.kind) {
                LinkKind.DOOR -> drawLine(Green.copy(alpha = 0.6f), a, b, thin)
                LinkKind.LADDER -> drawLine(DimGreen, a, b, thin * 2)
                LinkKind.DUCT -> drawLine(DimGreen, a, b, thin, pathEffect = dash)
                LinkKind.GRILLE -> {
                    // From the room box's corner to its duct marker: solid once the grille is off.
                    val room = if (link.from in layout.ducts) link.to else link.from
                    val open = rooms[room]?.grilleRemoved == true
                    drawLine(DimGreen, a, b, thin, pathEffect = if (open) null else dash)
                }
            }
        }

        // Rooms (and any duct with no room of its own) as boxes.
        for ((id, _) in layout.rooms) {
            val room = rooms[id]?.takeIf(view::shown) ?: continue
            drawRoom(room, geometry, view, measurer)
        }

        // Duct segments as small markers, with whoever is visibly inside.
        for ((id, _) in layout.ducts) {
            val duct = rooms[id]?.takeIf(view::shown) ?: continue
            val centre = geometry.centre(id) ?: continue
            val colour = view.colour(duct)
            val half = geometry.ductSize / 2
            val topLeft = centre - Offset(half, half)
            drawRect(colour.copy(alpha = 0.3f), topLeft, Size(geometry.ductSize, geometry.ductSize))
            drawRect(colour, topLeft, Size(geometry.ductSize, geometry.ductSize), style = Stroke(thin))
            val names = view.names(duct)
            if (names.isNotEmpty()) drawLabel(measurer, names, centre + Offset(half + 2.dp.toPx(), -half), colour)
        }
    }
}

/** A room box: outline and faded fill, its name, then any alarms and who's visibly there. */
private fun DrawScope.drawRoom(room: RoomState, geometry: Geometry, view: MapView, measurer: TextMeasurer) {
    val centre = geometry.centre(room.id) ?: return
    val colour = view.colour(room)
    val topLeft = centre - Offset(geometry.boxSize.width / 2, geometry.boxSize.height / 2)
    val selected = room.id == view.selectedRoom
    // An opaque base under the faded fill, so lines stop at the box's edge rather than showing through it.
    drawRect(Background, topLeft, geometry.boxSize)
    drawRect(colour.copy(alpha = 0.12f), topLeft, geometry.boxSize)
    drawRect(colour, topLeft, geometry.boxSize, style = Stroke((if (selected) 2 else 1).dp.toPx()))

    val inset = 3.dp.toPx()
    val textWidth = (geometry.boxSize.width - 2 * inset).toInt().coerceAtLeast(1)
    val marker = if (room.id in view.destinations) "> " else ""
    val nameLine = drawLabel(measurer, marker + room.name, topLeft + Offset(inset, inset), colour, bold = selected, maxWidth = textWidth)

    val alarms = view.alarms(room)
    val names = view.names(room)
    if (alarms.isEmpty() && names.isEmpty()) return
    val second = buildAnnotatedString {
        if (alarms.isNotEmpty()) withStyle(SpanStyle(color = Amber, fontWeight = FontWeight.Bold)) { append("$alarms ") }
        append(names)
    }
    val text = measurer.measure(second, labelStyle(colour), overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = textWidth))
    drawText(text, topLeft = topLeft + Offset(inset, inset + nameLine))
}

/** Draws one line of text at [topLeft]; returns its height. */
private fun DrawScope.drawLabel(
    measurer: TextMeasurer, text: String, topLeft: Offset, colour: Color, bold: Boolean = false,
    maxWidth: Int = (size.width - topLeft.x).toInt().coerceAtLeast(1),
): Float {
    val measured = measurer.measure(text, labelStyle(colour, bold), overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth))
    drawText(measured, topLeft = topLeft)
    return measured.size.height.toFloat()
}

private fun labelStyle(colour: Color, bold: Boolean = false) =
    TextStyle(color = colour, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

/** Pixel positions for [layout] in a canvas of [size], with a label gutter of [labelHeight] above each band. */
private class Geometry(private val layout: MapLayout, size: Size, private val labelHeight: Float) {
    private val cellWidth = size.width / layout.cols.coerceAtLeast(1)
    private val cellHeight = (size.height - labelHeight * layout.bands.size) / layout.rows.coerceAtLeast(1)
    val boxSize = Size(cellWidth * 0.8f, cellHeight * 0.62f)
    val ductSize = minOf(cellWidth, cellHeight) * 0.12f

    /** Which band each grid row is in. */
    private val bandOfRow = layout.bands.flatMapIndexed { index, band -> List(band.rows) { index } }

    fun bandTop(index: Int) = layout.bands[index].firstRow * cellHeight + index * labelHeight

    /** The centre of a room's box, or of a duct's marker (just off its room box's bottom-right corner). */
    fun centre(id: RoomId): Offset? {
        layout.rooms[id]?.let { cell ->
            val y = bandTop(bandOfRow[cell.row]) + labelHeight + (cell.row - layout.bands[bandOfRow[cell.row]].firstRow + 0.5f) * cellHeight
            return Offset((cell.col + 0.5f) * cellWidth, y)
        }
        val room = layout.ducts[id] ?: return null
        val roomCentre = centre(room) ?: return null
        return roomCentre + Offset(boxSize.width / 2 + ductSize, boxSize.height / 2 + ductSize)
    }

    /** Where a tap on a room's box or a duct's marker lands, at least [minSize] across so small ducts are easy to hit. */
    fun bounds(id: RoomId, minSize: Float): Rect? {
        val centre = centre(id) ?: return null
        val drawn = if (id in layout.rooms) boxSize else Size(ductSize, ductSize)
        val size = Size(maxOf(drawn.width, minSize), maxOf(drawn.height, minSize))
        return Rect(centre - Offset(size.width / 2, size.height / 2), size)
    }
}
