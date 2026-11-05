package org.tadams.nostromo.game

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.scenario.ScenarioLoader

/** The schematic layout of the bundled scenario: everything placed, nothing overlapping, each room in its deck. */
class OriginalMapLayoutTest {
    private val scenario = ScenarioLoader.fromJson(File("src/commonMain/composeResources/files/scenarios/original.json").readText())
    private val layout = layoutMap(scenario)

    @Test
    fun everyLocationIsPlaced() {
        assertEquals(scenario.rooms.map { it.id }.toSet(), layout.rooms.keys + layout.ducts.keys)
    }

    @Test
    fun noTwoRoomsShareACell() {
        assertEquals(layout.rooms.size, layout.rooms.values.toSet().size)
    }

    @Test
    fun roomsAreInTheirDeckBand() {
        for (room in scenario.rooms.filter { it.id in layout.rooms }) {
            val band = layout.bands.single { it.deck == room.deck }
            val row = layout.rooms.getValue(room.id).row
            assertTrue(row in band.firstRow until band.firstRow + band.rows, "${room.id} row $row not in $band")
        }
        assertEquals(listOf("upper", "middle", "lower", "shuttle"), layout.bands.map { it.deck })
    }
}
