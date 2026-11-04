package org.tadams.nostromo

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nostromo KMP",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
        App()
    }
}
