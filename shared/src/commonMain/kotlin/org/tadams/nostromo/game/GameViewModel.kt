package org.tadams.nostromo.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tadams.nostromo.core.GameState
import org.tadams.nostromo.core.Simulation
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.ActionOption
import org.tadams.nostromo.core.action.Command
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.Scenario

/** One line in the on-screen log. */
data class LogEntry(val tick: Long, val text: String, val hidden: Boolean)

/** Everything the game screen draws. */
data class GameUiState(
    val game: GameState,
    val selected: ActorId?,
    val actions: List<ActionOption>,
    val log: List<LogEntry>,
    val debug: Boolean,
    /** The scenario being played, for static data such as crew stats. */
    val scenario: Scenario,
)

/**
 * Runs the [Simulation] at its fixed 10 ticks a second and turns it into [GameUiState].
 *
 * Everything here runs on the main dispatcher (viewModelScope), which keeps calls into the
 * simulation on one thread, as [Simulation] requires.
 */
class GameViewModel(private val scenario: Scenario, seed: Long) : ViewModel() {

    private val sim = Simulation(scenario, seed)
    private val crew = scenario.actors.filter { it.kind == ActorKind.CREW }.map { it.id }

    private val narrator = Narrator(sim.text)

    /** Every log entry so far, hidden ones included, so the debug switch can re-filter the whole log. */
    private val allEntries = mutableListOf<LogEntry>()

    /** The log as last built; only rebuilt in full when the debug switch changes. */
    private var log = emptyList<LogEntry>()

    private val _state = MutableStateFlow(snapshot(selected = crew.firstOrNull(), debug = false))
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private var loop: Job? = null

    init {
        resume()
    }

    /** Starts (or restarts) the tick loop. Catches up on missed ticks so the rate stays steady. */
    fun resume() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            val tickLength = 1000L / Time.TICKS_PER_SECOND
            val start = TimeSource.Monotonic.markNow()
            var done = 0L
            while (isActive && sim.result == null) {
                val due = start.elapsedNow().inWholeMilliseconds / tickLength
                // Cap catch-up so a stalled frame can't run minutes of game at once.
                repeat((due - done).coerceIn(0, MAX_CATCH_UP).toInt()) { record(sim.tick()) }
                done = due
                refresh()
                delay(tickLength)
            }
            refresh()
        }
    }

    /** Stops the tick loop, e.g. when the window or tab is hidden. */
    fun pause() {
        loop?.cancel()
        loop = null
    }

    fun select(actor: ActorId) = _state.update { snapshot(actor, it.debug) }

    /** Selects the next (or previous, for [step] = -1) crew member, wrapping round. */
    fun cycleSelection(step: Int) {
        if (crew.isEmpty()) return
        val current = crew.indexOf(_state.value.selected).coerceAtLeast(0)
        select(crew[(current + step).mod(crew.size)])
    }

    /** Selects the crew member at [index] (0-based), if there is one. */
    fun selectIndex(index: Int) {
        crew.getOrNull(index)?.let(::select)
    }

    fun setDebug(on: Boolean) {
        log = if (on) allEntries.toList() else allEntries.filterNot { it.hidden }
        _state.update { snapshot(it.selected, on) }
    }

    /** Orders the selected crew member to do [action], replacing whatever they're doing. */
    fun order(action: Action) {
        val actor = _state.value.selected ?: return
        sim.submit(Command.Order(actor, action))
    }

    fun cancel() {
        val actor = _state.value.selected ?: return
        sim.submit(Command.Cancel(actor))
    }

    private fun record(events: List<GameEvent>) {
        val entries = events.flatMap(narrator::narrate)
        allEntries += entries
        log = log + if (_state.value.debug) entries else entries.filterNot { it.hidden }
    }

    private fun refresh() = _state.update { snapshot(it.selected, it.debug) }

    private fun snapshot(selected: ActorId?, debug: Boolean) = GameUiState(
        game = sim.state(),
        selected = selected,
        actions = selected?.let(sim::availableActions).orEmpty(),
        log = log,
        debug = debug,
        scenario = scenario,
    )

    private companion object {
        const val MAX_CATCH_UP = 50L
    }
}
