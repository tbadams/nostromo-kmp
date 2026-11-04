package org.tadams.nostromo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.scenario.ScenarioLoader
import org.tadams.nostromo.core.scenario.ScenarioValidator

/** The bundled scenario lives in :shared's resources; this checks it loads and plays. */
class OriginalScenarioTest {

    private val file = File("../shared/src/commonMain/composeResources/files/scenarios/original.json")

    private val scenario = ScenarioLoader.fromJson(file.readText())

    @Test
    fun loadsAndValidates() {
        assertEquals(emptyList(), ScenarioValidator.problems(scenario))
        assertEquals(7, scenario.actors.count { it.kind == org.tadams.nostromo.core.scenario.ActorKind.CREW })
    }

    @Test
    fun runsWithoutErrors() {
        val sim = Simulation(scenario, seed = 1)
        sim.runTicks(10 * 60 * 10) // ten minutes of game time
        assertTrue(sim.tick >= 1)
    }
}
