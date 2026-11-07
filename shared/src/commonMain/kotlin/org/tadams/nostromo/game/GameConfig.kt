package org.tadams.nostromo.game

import org.tadams.nostromo.core.scenario.Scenario

/**
 * The bundled scenarios, as files under `composeResources/files/scenarios/`. Compose resources can't
 * list a folder at runtime (not on the web), so they're named here; `BundledScenariosTest` checks
 * this matches the folder. The first is the default game.
 */
val BUNDLED_SCENARIOS = listOf("original.json", "short.json")

/** Resource path of a bundled scenario [file]. */
fun scenarioPath(file: String) = "files/scenarios/$file"

/**
 * How to set up a game: which scenario, and overrides to its rules. Null overrides keep the
 * scenario's own value. Chosen on the Custom Game screen; a New Game from the end screen reuses it.
 */
data class GameConfig(
    /** One of [BUNDLED_SCENARIOS]. */
    val scenarioFile: String = BUNDLED_SCENARIOS.first(),
    /** Oxygen at the start, replacing the scenario's [org.tadams.nostromo.core.scenario.Rules.oxygenStart]. */
    val oxygenStart: Int? = null,
    /** Whether crew use oxygen, replacing [org.tadams.nostromo.core.scenario.Rules.oxygenUsed]. Off: it never runs out. */
    val oxygenUsed: Boolean? = null,
) {
    /** [scenario] with this config's overrides applied. */
    fun apply(scenario: Scenario): Scenario = scenario.copy(
        rules = scenario.rules.copy(
            oxygenStart = oxygenStart ?: scenario.rules.oxygenStart,
            oxygenUsed = oxygenUsed ?: scenario.rules.oxygenUsed,
        ),
    )
}
