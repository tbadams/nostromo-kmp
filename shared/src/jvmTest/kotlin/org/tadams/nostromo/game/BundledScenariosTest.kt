package org.tadams.nostromo.game

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.tadams.nostromo.core.scenario.ScenarioLoader
import org.tadams.nostromo.core.scenario.ScenarioValidator

/** [BUNDLED_SCENARIOS] names every scenario file, and each one loads; [GameConfig] applies its overrides. */
class BundledScenariosTest {
    private val folder = File("src/commonMain/composeResources/files/scenarios")

    @Test
    fun listMatchesTheFolder() {
        val files = folder.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.sorted()
        assertEquals(files, BUNDLED_SCENARIOS.sorted(), "add new scenario files to BUNDLED_SCENARIOS")
    }

    @Test
    fun everyScenarioLoads() {
        for (name in BUNDLED_SCENARIOS) {
            val scenario = ScenarioLoader.fromJson(File(folder, name).readText())
            assertEquals(emptyList(), ScenarioValidator.problems(scenario), name)
        }
    }

    @Test
    fun configOverridesOxygen() {
        val scenario = ScenarioLoader.fromJson(File(folder, BUNDLED_SCENARIOS.first()).readText())
        assertEquals(scenario.rules, GameConfig().apply(scenario).rules, "no overrides: unchanged")
        val custom = GameConfig(oxygenStart = 1234, oxygenUsed = false).apply(scenario).rules
        assertEquals(1234, custom.oxygenStart)
        assertFalse(custom.oxygenUsed)
    }
}
