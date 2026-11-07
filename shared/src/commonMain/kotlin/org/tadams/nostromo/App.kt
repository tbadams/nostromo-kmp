package org.tadams.nostromo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import nostromokmp.shared.generated.resources.Res
import org.tadams.nostromo.core.scenario.Scenario
import org.tadams.nostromo.core.scenario.ScenarioLoader
import org.tadams.nostromo.game.BUNDLED_SCENARIOS
import org.tadams.nostromo.game.GameConfig
import org.tadams.nostromo.game.GameScreen
import org.tadams.nostromo.game.GameViewModel
import org.tadams.nostromo.game.scenarioPath
import org.tadams.nostromo.menu.CustomGameScreen
import org.tadams.nostromo.menu.InstructionsScreen
import org.tadams.nostromo.menu.MainMenu
import org.tadams.nostromo.ui.Background
import org.tadams.nostromo.ui.Green
import kotlin.random.Random

/** Which screen is showing. The game starts at the main menu. */
private enum class Screen { MENU, CUSTOM, INSTRUCTIONS, GAME }

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // Every bundled scenario, by file name, in BUNDLED_SCENARIOS order. They're small, and the
        // Custom Game screen needs their names and default rules.
        var scenarios by remember { mutableStateOf<Map<String, Scenario>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            runCatching {
                BUNDLED_SCENARIOS.associateWith { ScenarioLoader.fromJson(Res.readBytes(scenarioPath(it)).decodeToString()) }
            }
                .onSuccess { scenarios = it }
                .onFailure { error = it.message ?: it.toString() }
        }
        var screen by remember { mutableStateOf(Screen.MENU) }
        // The game being played: a New Game from the end screen keeps it and changes only the seed.
        var config by remember { mutableStateOf(GameConfig()) }
        // A new seed is a new game: keying the view model on it gives a fresh one.
        var seed by remember { mutableStateOf(Random.nextLong()) }
        fun start(with: GameConfig) {
            config = with
            seed = Random.nextLong()
            screen = Screen.GAME
        }

        val loaded = scenarios
        when {
            loaded == null -> Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                Text(error ?: "Loading…", color = Green)
            }
            screen == Screen.MENU -> MainMenu(
                onStart = { start(GameConfig()) },
                onCustom = { screen = Screen.CUSTOM },
                onInstructions = { screen = Screen.INSTRUCTIONS },
            )
            screen == Screen.CUSTOM -> CustomGameScreen(loaded, config, onStart = ::start, onBack = { screen = Screen.MENU })
            screen == Screen.INSTRUCTIONS -> InstructionsScreen(onBack = { screen = Screen.MENU })
            else -> {
                val scenario = config.apply(loaded.getValue(config.scenarioFile))
                GameScreen(viewModel(key = "game-$seed") { GameViewModel(scenario, seed) }, onRestart = { seed = Random.nextLong() })
            }
        }
    }
}
