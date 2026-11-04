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
import org.tadams.nostromo.game.GameScreen
import org.tadams.nostromo.game.GameViewModel
import org.tadams.nostromo.ui.Background
import org.tadams.nostromo.ui.Green
import kotlin.random.Random

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var scenario by remember { mutableStateOf<Scenario?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            runCatching { ScenarioLoader.fromJson(Res.readBytes("files/scenarios/original.json").decodeToString()) }
                .onSuccess { scenario = it }
                .onFailure { error = it.message ?: it.toString() }
        }
        val loaded = scenario
        when {
            loaded != null -> {
                val seed = remember { Random.nextLong() }
                GameScreen(viewModel { GameViewModel(loaded, seed) })
            }
            else -> Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                Text(error ?: "Loading…", color = Green)
            }
        }
    }
}
